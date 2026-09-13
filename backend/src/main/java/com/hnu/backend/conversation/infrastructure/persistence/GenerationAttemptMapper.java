package com.hnu.backend.conversation.infrastructure.persistence;

import com.hnu.backend.conversation.domain.GenerationAttempt;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface GenerationAttemptMapper {
  @Insert(
      """
      INSERT INTO generation_attempts(id, assistant_message_id, attempt_index, reason,
          model_id, provider, model, status, content)
      VALUES(#{a.id}, #{a.assistantMessageId}, #{a.attemptIndex}, #{a.reason},
          #{a.modelId}, #{a.provider}, #{a.model}, #{a.status}, #{a.content})
      """)
  int insert(@Param("a") GenerationAttempt attempt);

  @Update("UPDATE generation_attempts SET content=#{content} WHERE id=#{id}")
  int checkpoint(@Param("id") UUID id, @Param("content") String content);

  @Update(
      """
      UPDATE generation_attempts SET status='COMPLETED', content=#{content},
          finish_reason=#{finishReason}, completed_at=now() WHERE id=#{id}
      """)
  int complete(
      @Param("id") UUID id,
      @Param("content") String content,
      @Param("finishReason") String finishReason);

  @Update(
      """
      UPDATE generation_attempts SET status=#{status}, content=#{content}, error_code=#{code},
          error_message=#{message}, completed_at=now() WHERE id=#{id}
      """)
  int fail(
      @Param("id") UUID id,
      @Param("status") String status,
      @Param("content") String content,
      @Param("code") String code,
      @Param("message") String message);

  @Update(
      """
      UPDATE generation_attempts SET status='FAILED', error_code='GENERATION_INTERRUPTED',
          error_message='应用重启中断了生成', completed_at=now()
      WHERE status='STREAMING'
      """)
  int recoverInterrupted();
}
