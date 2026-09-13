package com.hnu.backend.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hnu.backend.conversation.entity.Conversation;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
  default int insert(UUID id, String title) {
    Conversation conversation = new Conversation();
    conversation.setId(id);
    conversation.setTitle(title);
    return insert(conversation);
  }

  default Conversation find(UUID id) {
    return selectById(id);
  }

  default List<Conversation> list(String query, int limit) {
    return selectList(
        Wrappers.<Conversation>lambdaQuery()
            .apply(
                query != null && !query.isEmpty(),
                "lower(title) LIKE '%' || lower({0}) || '%' ESCAPE '\\'",
                query)
            .orderByDesc(Conversation::getUpdatedAt)
            .orderByAsc(Conversation::getId)
            .last("LIMIT " + limit));
  }

  default int rename(UUID id, String title) {
    return update(
        Wrappers.<Conversation>lambdaUpdate()
            .eq(Conversation::getId, id)
            .set(Conversation::getTitle, title)
            .setSql("updated_at = now()"));
  }

  default int touch(UUID id) {
    return update(
        Wrappers.<Conversation>lambdaUpdate()
            .eq(Conversation::getId, id)
            .setSql("updated_at = now()"));
  }

  default int updateSummary(UUID id, String summaryJson, int throughTurn, int expectedRevision) {
    return update(
        Wrappers.<Conversation>update()
            .eq("id", id)
            .eq("summary_revision", expectedRevision)
            .setSql("summary = CAST({0} AS jsonb)", summaryJson)
            .set("summarized_through_turn", throughTurn)
            .setSql("summary_revision = summary_revision + 1")
            .setSql("updated_at = now()"));
  }

  default int delete(UUID id) {
    return deleteById(id);
  }
}
