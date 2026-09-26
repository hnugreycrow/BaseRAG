package com.hnu.backend.conversation.generation;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import com.hnu.backend.common.web.RequestTiming;
import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.entity.MessageRole;
import com.hnu.backend.conversation.entity.MessageStatus;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.observability.service.RagTraceManager;
import com.hnu.backend.observability.trace.RagRunTrace;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/** 创建生成前的消息、回答版本与 Trace；调用方负责持有会话锁并完成所有权、幂等和空闲校验。 */
final class ConversationGenerationPreparation {
  private final ConversationMapper conversationMapper;
  private final MessageMapper messageMapper;
  private final TransactionTemplate tx;
  private final RagTraceManager traces;

  /** 组装生成准备依赖，消息写入与 Trace 创建沿用同一事务。 */
  ConversationGenerationPreparation(
      ConversationMapper conversationMapper,
      MessageMapper messageMapper,
      TransactionTemplate tx,
      RagTraceManager traces) {
    this.conversationMapper = conversationMapper;
    this.messageMapper = messageMapper;
    this.tx = tx;
    this.traces = traces;
  }

  /**
   * 在同一事务内创建新一轮用户消息、待生成回答和 Trace。
   *
   * @param ownerId 已校验的所属用户标识
   * @param conversation 已校验归属且无活动生成的会话
   * @param clientMessageId 尚未使用的客户端幂等标识
   * @param question 已归一化并通过长度校验的问题
   * @param timing HTTP 请求起始信息
   * @return 已持久化的消息与生成 Trace
   */
  Prepared prepareNew(
      UUID ownerId,
      Conversation conversation,
      UUID clientMessageId,
      String question,
      RequestTiming timing) {
    UUID conversationId = conversation.getId();
    Prepared prepared =
        tx.execute(
            ignored -> {
              int turn = messageMapper.nextTurn(ownerId, conversationId);
              Message user = userMessage(conversationId, clientMessageId, turn, question);
              messageMapper.insert(user);
              Message assistant = assistantMessage(conversationId, null, turn, 1, user.getId());
              assistant.setThinkingEnabled(conversation.isThinkingEnabled());
              messageMapper.insert(assistant);
              RagRunTrace trace =
                  traces.start(
                      ownerId, conversationId, user.getId(), assistant.getId(), question, timing);
              conversationMapper.touch(ownerId, conversationId);
              return new Prepared(user, assistant, trace);
            });
    if (prepared == null) {
      throw new IllegalStateException("创建会话消息事务未返回结果");
    }
    return prepared;
  }

  /**
   * 校验原回答后，在同一事务内停用旧回答并创建新版本与 Trace。
   *
   * @param ownerId 已校验的所属用户标识
   * @param conversation 已校验归属且无活动生成的会话
   * @param assistantMessageId 待重试或重新生成的回答标识
   * @param clientRequestId 尚未使用的客户端幂等标识
   * @param timing HTTP 请求起始信息
   * @param regenerate true 表示重新生成最后一轮当前成功回答，false 表示重试失败或取消的回答
   * @return 原用户消息、新回答与生成 Trace
   * @throws ApiException 原消息不存在或回答状态不允许当前操作
   */
  Prepared prepareRestart(
      UUID ownerId,
      Conversation conversation,
      UUID assistantMessageId,
      UUID clientRequestId,
      RequestTiming timing,
      boolean regenerate) {
    UUID conversationId = conversation.getId();
    Message previous = messageMapper.find(ownerId, assistantMessageId);
    if (previous == null
        || !conversationId.equals(previous.getConversationId())
        || previous.getRole() != MessageRole.ASSISTANT) {
      throw ApiException.notFound(ErrorCode.MESSAGE_NOT_FOUND, "回答不存在");
    }
    if (regenerate) {
      int lastTurn = messageMapper.nextTurn(ownerId, conversationId) - 1;
      if (!previous.isActive()
          || previous.getStatus() != MessageStatus.COMPLETED
          || previous.getTurnIndex() != lastTurn) {
        throw ApiException.conflict(ErrorCode.REGENERATE_NOT_ALLOWED, "只能重新生成会话最后一轮的当前成功回答");
      }
    } else if (!(previous.getStatus() == MessageStatus.FAILED
        || previous.getStatus() == MessageStatus.CANCELLED)) {
      throw ApiException.conflict(ErrorCode.RETRY_NOT_ALLOWED, "只能重试失败或已停止的回答");
    }
    Message user = messageMapper.find(ownerId, previous.getReplyToId());
    if (user == null) {
      throw ApiException.notFound(ErrorCode.MESSAGE_NOT_FOUND, "原用户消息不存在");
    }
    Prepared next =
        tx.execute(
            ignored -> {
              messageMapper.deactivateReplies(ownerId, user.getId());
              Message value =
                  assistantMessage(
                      conversationId,
                      clientRequestId,
                      user.getTurnIndex(),
                      messageMapper.nextVariant(ownerId, user.getId()),
                      user.getId());
              value.setThinkingEnabled(conversation.isThinkingEnabled());
              messageMapper.insert(value);
              RagRunTrace trace =
                  traces.start(
                      ownerId,
                      conversationId,
                      user.getId(),
                      value.getId(),
                      user.getContent(),
                      timing);
              conversationMapper.touch(ownerId, conversationId);
              return new Prepared(user, value, trace);
            });
    if (next == null) {
      throw new IllegalStateException("创建回答版本事务未返回结果");
    }
    return next;
  }

  /**
   * 创建尚未写入数据库的用户消息实体。
   *
   * @param conversationId 会话 ID
   * @param clientId 客户端幂等 ID
   * @param turn 轮次编号
   * @param content 用户问题
   * @return 初始化完成的用户消息
   */
  private Message userMessage(UUID conversationId, UUID clientId, int turn, String content) {
    Message value = new Message();
    value.setId(UUID.randomUUID());
    value.setConversationId(conversationId);
    value.setClientRequestId(clientId);
    value.setRole(MessageRole.USER);
    value.setTurnIndex(turn);
    value.setVariantIndex(0);
    value.setActive(true);
    value.setStatus(MessageStatus.COMPLETED);
    value.setContent(content);
    value.setSourcesJson("[]");
    value.setCitationsJson("[]");
    return value;
  }

  /**
   * 创建尚未写入数据库的待生成回答实体。
   *
   * @param conversationId 会话 ID
   * @param clientId 客户端幂等 ID
   * @param turn 轮次编号
   * @param variant 回答版本编号
   * @param replyTo 对应用户消息 ID
   * @return 初始化完成的回答消息
   */
  private Message assistantMessage(
      UUID conversationId, UUID clientId, int turn, int variant, UUID replyTo) {
    Message value = new Message();
    value.setId(UUID.randomUUID());
    value.setConversationId(conversationId);
    value.setClientRequestId(clientId);
    value.setRole(MessageRole.ASSISTANT);
    value.setTurnIndex(turn);
    value.setVariantIndex(variant);
    value.setActive(true);
    value.setReplyToId(replyTo);
    value.setStatus(MessageStatus.PENDING);
    value.setContent("");
    value.setReasoningContent("");
    value.setSourcesJson("[]");
    value.setCitationsJson("[]");
    return value;
  }

  /**
   * 事务准备完成后交给生成协调器的数据。
   *
   * @param user 新建或复用的用户消息
   * @param assistant 已持久化的待生成回答
   * @param trace 与回答版本一一对应的 Trace
   */
  record Prepared(Message user, Message assistant, RagRunTrace trace) {}
}
