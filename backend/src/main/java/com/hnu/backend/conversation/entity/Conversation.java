package com.hnu.backend.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hnu.backend.shared.persistence.PostgresTextTypeHandler;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

/** 会话实体，保存标题以及可增量更新的历史摘要。 */
@Data
@TableName(value = "conversations", autoResultMap = true)
public class Conversation {
  /** 会话标识。 */
  @TableId(type = IdType.INPUT)
  private UUID id;

  /** 所属用户标识。 */
  private UUID ownerId;

  /** 会话标题。 */
  private String title;

  /** 当前会话的深度思考选择。 */
  private boolean thinkingEnabled;

  /** 早期对话摘要的 JSON 表示。 */
  @TableField(
      value = "summary",
      jdbcType = JdbcType.OTHER,
      typeHandler = PostgresTextTypeHandler.class)
  private String summaryJson;

  /** 摘要已经覆盖到的最大轮次。 */
  private int summarizedThroughTurn;

  /** 摘要乐观锁版本号，用于阻止并发更新覆盖。 */
  private int summaryRevision;

  /** 创建时间。 */
  private OffsetDateTime createdAt;

  /** 最近更新时间。 */
  private OffsetDateTime updatedAt;
}
