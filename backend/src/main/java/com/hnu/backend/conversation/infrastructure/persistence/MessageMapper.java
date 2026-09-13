package com.hnu.backend.conversation.infrastructure.persistence;

import com.hnu.backend.conversation.domain.Message;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MessageMapper {
  String SELECT_FIELDS =
      """
      id, conversation_id, client_request_id, role, turn_index, variant_index, active,
      reply_to_id, status, content, retrieval_query, sources::text AS sources_json,
      citations::text AS citations_json, model_info::text AS model_info_json,
      error_code, error_message, created_at, updated_at, completed_at
      """;

  @Insert(
      """
      INSERT INTO messages(id, conversation_id, client_request_id, role, turn_index,
          variant_index, active, reply_to_id, status, content, retrieval_query,
          sources, citations, model_info)
      VALUES(#{m.id}, #{m.conversationId}, #{m.clientRequestId}, #{m.role}, #{m.turnIndex},
          #{m.variantIndex}, #{m.active}, #{m.replyToId}, #{m.status}, #{m.content},
          #{m.retrievalQuery}, CAST(#{m.sourcesJson} AS jsonb),
          CAST(#{m.citationsJson} AS jsonb), CAST(#{m.modelInfoJson} AS jsonb))
      """)
  int insert(@Param("m") Message message);

  @Select("SELECT " + SELECT_FIELDS + " FROM messages WHERE id=#{id}")
  Message find(@Param("id") UUID id);

  @Select(
      "SELECT "
          + SELECT_FIELDS
          + " FROM messages WHERE conversation_id=#{conversationId} ORDER BY turn_index, role DESC, variant_index")
  List<Message> list(@Param("conversationId") UUID conversationId);

  @Select(
      "SELECT "
          + SELECT_FIELDS
          + " FROM messages WHERE conversation_id=#{conversationId} AND client_request_id=#{clientRequestId} LIMIT 1")
  Message findByClientRequest(
      @Param("conversationId") UUID conversationId, @Param("clientRequestId") UUID clientRequestId);

  @Select(
      "SELECT "
          + SELECT_FIELDS
          + " FROM messages WHERE reply_to_id=#{userMessageId} ORDER BY variant_index DESC LIMIT 1")
  Message latestReply(@Param("userMessageId") UUID userMessageId);

  @Select(
      "SELECT COALESCE(MAX(turn_index),0)+1 FROM messages WHERE conversation_id=#{conversationId} AND role='USER'")
  int nextTurn(@Param("conversationId") UUID conversationId);

  @Select(
      "SELECT COALESCE(MAX(variant_index),0)+1 FROM messages WHERE reply_to_id=#{userMessageId} AND role='ASSISTANT'")
  int nextVariant(@Param("userMessageId") UUID userMessageId);

  @Update(
      "UPDATE messages SET active=false, updated_at=now() WHERE reply_to_id=#{userMessageId} AND role='ASSISTANT' AND active")
  int deactivateReplies(@Param("userMessageId") UUID userMessageId);

  @Update(
      """
      UPDATE messages SET retrieval_query=#{query}, sources=CAST(#{sourcesJson} AS jsonb),
          updated_at=now() WHERE id=#{id}
      """)
  int prepare(
      @Param("id") UUID id, @Param("query") String query, @Param("sourcesJson") String sourcesJson);

  @Update("UPDATE messages SET status='STREAMING', updated_at=now() WHERE id=#{id}")
  int markStreaming(@Param("id") UUID id);

  @Update(
      "UPDATE messages SET model_info=CAST(#{modelInfoJson} AS jsonb), updated_at=now() WHERE id=#{id}")
  int setModelInfo(@Param("id") UUID id, @Param("modelInfoJson") String modelInfoJson);

  @Update("UPDATE messages SET content=#{content}, updated_at=now() WHERE id=#{id}")
  int checkpoint(@Param("id") UUID id, @Param("content") String content);

  @Update(
      """
      UPDATE messages SET status='COMPLETED', content=#{content},
          citations=CAST(#{citationsJson} AS jsonb), model_info=CAST(#{modelInfoJson} AS jsonb),
          error_code=NULL, error_message=NULL, updated_at=now(), completed_at=now()
      WHERE id=#{id}
      """)
  int complete(
      @Param("id") UUID id,
      @Param("content") String content,
      @Param("citationsJson") String citationsJson,
      @Param("modelInfoJson") String modelInfoJson);

  @Update(
      """
      UPDATE messages SET status=#{status}, content=#{content}, error_code=#{code},
          error_message=#{message}, updated_at=now(), completed_at=now() WHERE id=#{id}
      """)
  int terminalFailure(
      @Param("id") UUID id,
      @Param("status") String status,
      @Param("content") String content,
      @Param("code") String code,
      @Param("message") String message);

  @Update(
      """
      UPDATE messages SET status='FAILED', error_code='GENERATION_INTERRUPTED',
          error_message='应用重启中断了生成，请重试', updated_at=now(), completed_at=now()
      WHERE role='ASSISTANT' AND status IN ('PENDING','STREAMING')
      """)
  int recoverInterrupted();

  @Select(
      "SELECT count(*) FROM messages WHERE conversation_id=#{conversationId} AND role='ASSISTANT' AND status IN ('PENDING','STREAMING')")
  long countRunning(@Param("conversationId") UUID conversationId);
}
