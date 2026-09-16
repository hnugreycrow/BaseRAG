package com.hnu.backend.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.entity.MessageRole;
import com.hnu.backend.conversation.entity.MessageStatus;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;

/** 会话消息及助手回答状态迁移的数据访问接口。 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {
  /**
   * 按标识查询消息。
   *
   * @param ownerId 所属用户标识
   * @param id 消息标识
   * @return 消息；不存在时返回 {@code null}
   */
  default Message find(UUID ownerId, UUID id) {
    return selectOne(
        Wrappers.<Message>lambdaQuery()
            .eq(Message::getId, id)
            .apply(
                "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})", ownerId));
  }

  /**
   * 按轮次和回答版本顺序列出会话中的全部消息。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @return 有序消息列表
   */
  default List<Message> list(UUID ownerId, UUID conversationId) {
    return selectList(
        Wrappers.<Message>lambdaQuery()
            .eq(Message::getConversationId, conversationId)
            .apply(
                "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})", ownerId)
            .orderByAsc(Message::getTurnIndex)
            .orderByDesc(Message::getRole)
            .orderByAsc(Message::getVariantIndex));
  }

  /**
   * 使用客户端请求标识查询已创建的消息，以支持幂等重试。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @param clientRequestId 客户端请求标识
   * @return 已存在的消息；不存在时返回 {@code null}
   */
  default Message findByClientRequest(UUID ownerId, UUID conversationId, UUID clientRequestId) {
    return selectOne(
        Wrappers.<Message>lambdaQuery()
            .eq(Message::getConversationId, conversationId)
            .apply(
                "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})", ownerId)
            .eq(Message::getClientRequestId, clientRequestId));
  }

  /**
   * 查询指定用户消息的最新助手回答版本。
   *
   * @param ownerId 所属用户标识
   * @param userMessageId 用户消息标识
   * @return 最新回答；不存在时返回 {@code null}
   */
  default Message latestReply(UUID ownerId, UUID userMessageId) {
    return selectOne(
        Wrappers.<Message>lambdaQuery()
            .eq(Message::getReplyToId, userMessageId)
            .apply(
                "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})", ownerId)
            .orderByDesc(Message::getVariantIndex)
            .last("LIMIT 1"));
  }

  /**
   * 计算会话中下一个用户轮次序号。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @return 从 1 开始的下一个轮次序号
   */
  default int nextTurn(UUID ownerId, UUID conversationId) {
    Object value =
        selectObjs(
                Wrappers.<Message>query()
                    .select("COALESCE(MAX(turn_index), 0) + 1")
                    .eq("conversation_id", conversationId)
                    .apply(
                        "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})",
                        ownerId)
                    .eq("role", MessageRole.USER.name()))
            .getFirst();
    return ((Number) value).intValue();
  }

  /**
   * 计算指定用户消息的下一个助手回答版本序号。
   *
   * @param ownerId 所属用户标识
   * @param userMessageId 用户消息标识
   * @return 从 1 开始的下一个版本序号
   */
  default int nextVariant(UUID ownerId, UUID userMessageId) {
    Object value =
        selectObjs(
                Wrappers.<Message>query()
                    .select("COALESCE(MAX(variant_index), 0) + 1")
                    .eq("reply_to_id", userMessageId)
                    .apply(
                        "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})",
                        ownerId)
                    .eq("role", MessageRole.ASSISTANT.name()))
            .getFirst();
    return ((Number) value).intValue();
  }

  /**
   * 将指定用户消息下当前生效的助手回答全部停用。
   *
   * @param ownerId 所属用户标识
   * @param userMessageId 用户消息标识
   * @return 受影响行数
   */
  default int deactivateReplies(UUID ownerId, UUID userMessageId) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getReplyToId, userMessageId)
            .apply(
                "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})", ownerId)
            .eq(Message::getRole, MessageRole.ASSISTANT)
            .eq(Message::isActive, true)
            .set(Message::isActive, false)
            .setSql("updated_at = now()"));
  }

  /**
   * 保存生成前的检索问题与来源，仅允许更新尚未终止的回答。
   *
   * @param ownerId 所属用户标识
   * @param id 助手消息标识
   * @param query 实际检索问题
   * @param sourcesJson 检索来源的 JSON 表示
   * @return 受影响行数
   */
  default int prepare(UUID ownerId, UUID id, String query, String sourcesJson) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .apply(
                "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})", ownerId)
            .in(Message::getStatus, MessageStatus.PENDING, MessageStatus.STREAMING)
            .set(Message::getRetrievalQuery, query)
            .setSql("sources = CAST({0} AS jsonb)", sourcesJson)
            .setSql("updated_at = now()"));
  }

  /**
   * 将待处理回答原子地切换为流式生成状态。
   *
   * @param ownerId 所属用户标识
   * @param id 助手消息标识
   * @return 受影响行数；为 0 表示状态已变化
   */
  default int markStreaming(UUID ownerId, UUID id) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .apply(
                "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})", ownerId)
            .eq(Message::getStatus, MessageStatus.PENDING)
            .set(Message::getStatus, MessageStatus.STREAMING)
            .setSql("updated_at = now()"));
  }

  /**
   * 保存当前生成尝试所使用的模型信息。
   *
   * @param ownerId 所属用户标识
   * @param id 助手消息标识
   * @param modelInfoJson 模型信息的 JSON 表示
   * @return 受影响行数
   */
  default int setModelInfo(UUID ownerId, UUID id, String modelInfoJson) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .apply(
                "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})", ownerId)
            .in(Message::getStatus, MessageStatus.PENDING, MessageStatus.STREAMING)
            .setSql("model_info = CAST({0} AS jsonb)", modelInfoJson)
            .setSql("updated_at = now()"));
  }

  /**
   * 持久化流式生成的阶段性正文，仅允许更新运行中的回答。
   *
   * @param ownerId 所属用户标识
   * @param id 助手消息标识
   * @param content 当前完整正文
   * @return 受影响行数
   */
  default int checkpoint(UUID ownerId, UUID id, String content) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .apply(
                "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})", ownerId)
            .in(Message::getStatus, MessageStatus.PENDING, MessageStatus.STREAMING)
            .set(Message::getContent, content)
            .setSql("updated_at = now()"));
  }

  /** 保存运行中回答的思考内容检查点。 */
  default int checkpointReasoning(UUID ownerId, UUID id, String reasoning) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .apply(
                "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})", ownerId)
            .in(Message::getStatus, MessageStatus.PENDING, MessageStatus.STREAMING)
            .set(Message::getReasoningContent, reasoning)
            .setSql("updated_at = now()"));
  }

  /** 与回答终态更新同一事务保存最终或部分思考内容。 */
  default int saveReasoning(UUID ownerId, UUID id, String reasoning) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .eq(Message::getRole, MessageRole.ASSISTANT)
            .apply(
                "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})", ownerId)
            .set(Message::getReasoningContent, reasoning));
  }

  /**
   * 将运行中的助手回答原子地标记为完成并保存最终元数据。
   *
   * @param ownerId 所属用户标识
   * @param id 助手消息标识
   * @param content 最终正文
   * @param citationsJson 引用标识列表的 JSON 表示
   * @param modelInfoJson 模型信息的 JSON 表示；允许为空
   * @return 受影响行数
   */
  default int complete(
      UUID ownerId, UUID id, String content, String citationsJson, String modelInfoJson) {
    var wrapper =
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .apply(
                "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})", ownerId)
            .in(Message::getStatus, MessageStatus.PENDING, MessageStatus.STREAMING)
            .set(Message::getStatus, MessageStatus.COMPLETED)
            .set(Message::getContent, content)
            .setSql("citations = CAST({0} AS jsonb)", citationsJson)
            .set(Message::getErrorCode, null)
            .set(Message::getErrorMessage, null)
            .setSql("updated_at = now()")
            .setSql("completed_at = now()");
    // 显式写入 null，避免重试流程遗留前一次模型尝试的信息。
    if (modelInfoJson == null) {
      wrapper.set(Message::getModelInfoJson, null);
    } else {
      wrapper.setSql("model_info = CAST({0} AS jsonb)", modelInfoJson);
    }
    return update(wrapper);
  }

  /**
   * 将运行中的助手回答切换为失败或取消终态。
   *
   * @param ownerId 所属用户标识
   * @param id 助手消息标识
   * @param status 目标终态
   * @param content 失败前已生成的正文
   * @param code 错误码
   * @param message 错误信息
   * @return 受影响行数
   */
  default int terminalFailure(
      UUID ownerId, UUID id, MessageStatus status, String content, String code, String message) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .apply(
                "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})", ownerId)
            .in(Message::getStatus, MessageStatus.PENDING, MessageStatus.STREAMING)
            .set(Message::getStatus, status)
            .set(Message::getContent, content)
            .set(Message::getErrorCode, code)
            .set(Message::getErrorMessage, message)
            .setSql("updated_at = now()")
            .setSql("completed_at = now()"));
  }

  /**
   * 在会话范围内取消指定的运行中助手回答。
   *
   * @param ownerId 所属用户标识
   * @param id 助手消息标识
   * @param conversationId 会话标识，用于防止跨会话误操作
   * @param content 取消前已生成的正文
   * @return 受影响行数
   */
  default int cancelRunning(UUID ownerId, UUID id, UUID conversationId, String content) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .eq(Message::getConversationId, conversationId)
            .apply(
                "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})", ownerId)
            .eq(Message::getRole, MessageRole.ASSISTANT)
            .in(Message::getStatus, MessageStatus.PENDING, MessageStatus.STREAMING)
            .set(Message::getStatus, MessageStatus.CANCELLED)
            .set(Message::getContent, content)
            .set(Message::getErrorCode, "GENERATION_CANCELLED")
            .set(Message::getErrorMessage, "生成已停止")
            .setSql("updated_at = now()")
            .setSql("completed_at = now()"));
  }

  /**
   * 应用启动时将遗留的运行中回答统一恢复为失败终态。
   *
   * @return 恢复的消息数量
   */
  default int recoverInterrupted() {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getRole, MessageRole.ASSISTANT)
            .in(Message::getStatus, MessageStatus.PENDING, MessageStatus.STREAMING)
            .set(Message::getStatus, MessageStatus.FAILED)
            .set(Message::getErrorCode, "GENERATION_INTERRUPTED")
            .set(Message::getErrorMessage, "应用重启中断了生成，请重试")
            .setSql("updated_at = now()")
            .setSql("completed_at = now()"));
  }

  /**
   * 统计会话中仍在生成的助手回答数量。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @return 运行中回答数量
   */
  default long countRunning(UUID ownerId, UUID conversationId) {
    return selectCount(
        Wrappers.<Message>lambdaQuery()
            .eq(Message::getConversationId, conversationId)
            .apply(
                "conversation_id IN (SELECT id FROM conversations WHERE owner_id = {0})", ownerId)
            .eq(Message::getRole, MessageRole.ASSISTANT)
            .in(Message::getStatus, MessageStatus.PENDING, MessageStatus.STREAMING));
  }
}
