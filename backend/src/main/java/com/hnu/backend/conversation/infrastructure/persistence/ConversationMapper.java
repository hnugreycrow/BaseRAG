package com.hnu.backend.conversation.infrastructure.persistence;

import com.hnu.backend.conversation.domain.Conversation;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ConversationMapper {
  @Insert("INSERT INTO conversations(id, title) VALUES(#{id}, #{title})")
  int insert(@Param("id") UUID id, @Param("title") String title);

  @Select(
      """
      SELECT id, title, summary::text AS summary_json, summarized_through_turn,
             summary_revision, created_at, updated_at
      FROM conversations WHERE id = #{id}
      """)
  Conversation find(@Param("id") UUID id);

  @Select(
      """
      <script>
      SELECT id, title, summary::text AS summary_json, summarized_through_turn,
             summary_revision, created_at, updated_at
      FROM conversations
      <where>
        <if test="q != null and q != ''">lower(title) LIKE '%' || lower(#{q}) || '%' ESCAPE '\\'</if>
      </where>
      ORDER BY updated_at DESC, id
      LIMIT #{limit}
      </script>
      """)
  List<Conversation> list(@Param("q") String q, @Param("limit") int limit);

  @Update("UPDATE conversations SET title=#{title}, updated_at=now() WHERE id=#{id}")
  int rename(@Param("id") UUID id, @Param("title") String title);

  @Update("UPDATE conversations SET updated_at=now() WHERE id=#{id}")
  int touch(@Param("id") UUID id);

  @Update(
      """
      UPDATE conversations
      SET summary=CAST(#{summaryJson} AS jsonb), summarized_through_turn=#{throughTurn},
          summary_revision=summary_revision + 1, updated_at=now()
      WHERE id=#{id} AND summary_revision=#{expectedRevision}
      """)
  int updateSummary(
      @Param("id") UUID id,
      @Param("summaryJson") String summaryJson,
      @Param("throughTurn") int throughTurn,
      @Param("expectedRevision") int expectedRevision);

  @Delete("DELETE FROM conversations WHERE id=#{id}")
  int delete(@Param("id") UUID id);
}
