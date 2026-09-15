package com.hnu.backend.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hnu.backend.conversation.entity.Conversation;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;

/** 会话的数据访问接口。 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
  /**
   * 创建会话。
   *
   * @param ownerId 所属用户标识
   * @param id 会话标识
   * @param title 初始标题
   * @return 受影响行数
   */
  default int insert(UUID ownerId, UUID id, String title) {
    return insert(ownerId, id, title, false);
  }

  default int insert(UUID ownerId, UUID id, String title, boolean thinkingEnabled) {
    Conversation conversation = new Conversation();
    conversation.setId(id);
    conversation.setOwnerId(ownerId);
    conversation.setTitle(title);
    conversation.setThinkingEnabled(thinkingEnabled);
    return insert(conversation);
  }

  /**
   * 按标识查询会话。
   *
   * @param ownerId 所属用户标识
   * @param id 会话标识
   * @return 会话；不存在时返回 {@code null}
   */
  default Conversation find(UUID ownerId, UUID id) {
    return selectOne(
        Wrappers.<Conversation>lambdaQuery()
            .eq(Conversation::getId, id)
            .eq(Conversation::getOwnerId, ownerId));
  }

  /**
   * 按最近更新时间列出会话，可选按标题模糊搜索。
   *
   * @param ownerId 所属用户标识
   * @param query 可选的标题搜索词
   * @param limit 最大返回数量
   * @return 会话列表
   */
  default List<Conversation> list(UUID ownerId, String query, int limit) {
    return selectList(
        Wrappers.<Conversation>lambdaQuery()
            .eq(Conversation::getOwnerId, ownerId)
            .apply(
                query != null && !query.isEmpty(),
                "lower(title) LIKE '%' || lower({0}) || '%' ESCAPE '\\'",
                query)
            .orderByDesc(Conversation::getUpdatedAt)
            .orderByAsc(Conversation::getId)
            .last("LIMIT " + limit));
  }

  /**
   * 修改会话标题并刷新更新时间。
   *
   * @param ownerId 所属用户标识
   * @param id 会话标识
   * @param title 新标题
   * @return 受影响行数
   */
  default int rename(UUID ownerId, UUID id, String title) {
    return update(
        Wrappers.<Conversation>lambdaUpdate()
            .eq(Conversation::getId, id)
            .eq(Conversation::getOwnerId, ownerId)
            .set(Conversation::getTitle, title)
            .setSql("updated_at = now()"));
  }

  /** 按所属用户更新会话的深度思考选择。 */
  default int setThinkingEnabled(UUID ownerId, UUID id, boolean enabled) {
    return update(
        Wrappers.<Conversation>lambdaUpdate()
            .eq(Conversation::getId, id)
            .eq(Conversation::getOwnerId, ownerId)
            .set(Conversation::isThinkingEnabled, enabled)
            .setSql("updated_at = now()"));
  }

  /**
   * 刷新会话更新时间，使最近有活动的会话优先展示。
   *
   * @param ownerId 所属用户标识
   * @param id 会话标识
   * @return 受影响行数
   */
  default int touch(UUID ownerId, UUID id) {
    return update(
        Wrappers.<Conversation>lambdaUpdate()
            .eq(Conversation::getId, id)
            .eq(Conversation::getOwnerId, ownerId)
            .setSql("updated_at = now()"));
  }

  /**
   * 使用乐观锁更新会话摘要及其覆盖轮次。
   *
   * @param ownerId 所属用户标识
   * @param id 会话标识
   * @param summaryJson 新摘要的 JSON 表示
   * @param throughTurn 摘要覆盖到的最大轮次
   * @param expectedRevision 调用方读取到的摘要版本
   * @return 受影响行数；为 0 表示版本冲突或会话不存在
   */
  default int updateSummary(
      UUID ownerId, UUID id, String summaryJson, int throughTurn, int expectedRevision) {
    // revision 条件与自增必须在同一条 SQL 中完成，避免并发摘要相互覆盖。
    return update(
        Wrappers.<Conversation>update()
            .eq("id", id)
            .eq("owner_id", ownerId)
            .eq("summary_revision", expectedRevision)
            .setSql("summary = CAST({0} AS jsonb)", summaryJson)
            .set("summarized_through_turn", throughTurn)
            .setSql("summary_revision = summary_revision + 1")
            .setSql("updated_at = now()"));
  }

  /**
   * 删除会话。
   *
   * @param ownerId 所属用户标识
   * @param id 会话标识
   * @return 受影响行数
   */
  default int delete(UUID ownerId, UUID id) {
    return delete(
        Wrappers.<Conversation>lambdaQuery()
            .eq(Conversation::getId, id)
            .eq(Conversation::getOwnerId, ownerId));
  }
}
