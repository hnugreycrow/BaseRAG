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

@Data
@TableName(value = "conversations", autoResultMap = true)
public class Conversation {
  @TableId(type = IdType.INPUT)
  private UUID id;

  private String title;

  @TableField(
      value = "summary",
      jdbcType = JdbcType.OTHER,
      typeHandler = PostgresTextTypeHandler.class)
  private String summaryJson;

  private int summarizedThroughTurn;
  private int summaryRevision;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
}
