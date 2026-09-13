package com.hnu.backend.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hnu.backend.conversation.entity.Message;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
  default Message find(UUID id) {
    return selectById(id);
  }

  default List<Message> list(UUID conversationId) {
    return selectList(
        Wrappers.<Message>lambdaQuery()
            .eq(Message::getConversationId, conversationId)
            .orderByAsc(Message::getTurnIndex)
            .orderByDesc(Message::getRole)
            .orderByAsc(Message::getVariantIndex));
  }

  default Message findByClientRequest(UUID conversationId, UUID clientRequestId) {
    return selectOne(
        Wrappers.<Message>lambdaQuery()
            .eq(Message::getConversationId, conversationId)
            .eq(Message::getClientRequestId, clientRequestId));
  }

  default Message latestReply(UUID userMessageId) {
    return selectOne(
        Wrappers.<Message>lambdaQuery()
            .eq(Message::getReplyToId, userMessageId)
            .orderByDesc(Message::getVariantIndex)
            .last("LIMIT 1"));
  }

  default int nextTurn(UUID conversationId) {
    Object value =
        selectObjs(
                Wrappers.<Message>query()
                    .select("COALESCE(MAX(turn_index), 0) + 1")
                    .eq("conversation_id", conversationId)
                    .eq("role", "USER"))
            .getFirst();
    return ((Number) value).intValue();
  }

  default int nextVariant(UUID userMessageId) {
    Object value =
        selectObjs(
                Wrappers.<Message>query()
                    .select("COALESCE(MAX(variant_index), 0) + 1")
                    .eq("reply_to_id", userMessageId)
                    .eq("role", "ASSISTANT"))
            .getFirst();
    return ((Number) value).intValue();
  }

  default int deactivateReplies(UUID userMessageId) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getReplyToId, userMessageId)
            .eq(Message::getRole, "ASSISTANT")
            .eq(Message::isActive, true)
            .set(Message::isActive, false)
            .setSql("updated_at = now()"));
  }

  default int prepare(UUID id, String query, String sourcesJson) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .in(Message::getStatus, "PENDING", "STREAMING")
            .set(Message::getRetrievalQuery, query)
            .setSql("sources = CAST({0} AS jsonb)", sourcesJson)
            .setSql("updated_at = now()"));
  }

  default int markStreaming(UUID id) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .eq(Message::getStatus, "PENDING")
            .set(Message::getStatus, "STREAMING")
            .setSql("updated_at = now()"));
  }

  default int setModelInfo(UUID id, String modelInfoJson) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .in(Message::getStatus, "PENDING", "STREAMING")
            .setSql("model_info = CAST({0} AS jsonb)", modelInfoJson)
            .setSql("updated_at = now()"));
  }

  default int checkpoint(UUID id, String content) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .in(Message::getStatus, "PENDING", "STREAMING")
            .set(Message::getContent, content)
            .setSql("updated_at = now()"));
  }

  default int complete(UUID id, String content, String citationsJson, String modelInfoJson) {
    var wrapper =
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .in(Message::getStatus, "PENDING", "STREAMING")
            .set(Message::getStatus, "COMPLETED")
            .set(Message::getContent, content)
            .setSql("citations = CAST({0} AS jsonb)", citationsJson)
            .set(Message::getErrorCode, null)
            .set(Message::getErrorMessage, null)
            .setSql("updated_at = now()")
            .setSql("completed_at = now()");
    if (modelInfoJson == null) {
      wrapper.set(Message::getModelInfoJson, null);
    } else {
      wrapper.setSql("model_info = CAST({0} AS jsonb)", modelInfoJson);
    }
    return update(wrapper);
  }

  default int terminalFailure(UUID id, String status, String content, String code, String message) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .in(Message::getStatus, "PENDING", "STREAMING")
            .set(Message::getStatus, status)
            .set(Message::getContent, content)
            .set(Message::getErrorCode, code)
            .set(Message::getErrorMessage, message)
            .setSql("updated_at = now()")
            .setSql("completed_at = now()"));
  }

  default int cancelRunning(UUID id, UUID conversationId, String content) {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, id)
            .eq(Message::getConversationId, conversationId)
            .eq(Message::getRole, "ASSISTANT")
            .in(Message::getStatus, "PENDING", "STREAMING")
            .set(Message::getStatus, "CANCELLED")
            .set(Message::getContent, content)
            .set(Message::getErrorCode, "GENERATION_CANCELLED")
            .set(Message::getErrorMessage, "生成已停止")
            .setSql("updated_at = now()")
            .setSql("completed_at = now()"));
  }

  default int recoverInterrupted() {
    return update(
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getRole, "ASSISTANT")
            .in(Message::getStatus, "PENDING", "STREAMING")
            .set(Message::getStatus, "FAILED")
            .set(Message::getErrorCode, "GENERATION_INTERRUPTED")
            .set(Message::getErrorMessage, "应用重启中断了生成，请重试")
            .setSql("updated_at = now()")
            .setSql("completed_at = now()"));
  }

  default long countRunning(UUID conversationId) {
    return selectCount(
        Wrappers.<Message>lambdaQuery()
            .eq(Message::getConversationId, conversationId)
            .eq(Message::getRole, "ASSISTANT")
            .in(Message::getStatus, "PENDING", "STREAMING"));
  }
}
