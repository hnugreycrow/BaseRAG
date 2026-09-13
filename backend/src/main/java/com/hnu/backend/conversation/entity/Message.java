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
@TableName(value = "messages", autoResultMap = true)
public class Message {
  @TableId(type = IdType.INPUT)
  private UUID id;

  private UUID conversationId;
  private UUID clientRequestId;
  private String role;
  private int turnIndex;
  private int variantIndex;
  private boolean active;
  private UUID replyToId;
  private String status;
  private String content;
  private String retrievalQuery;

  @TableField(
      value = "sources",
      jdbcType = JdbcType.OTHER,
      typeHandler = PostgresTextTypeHandler.class)
  private String sourcesJson;

  @TableField(
      value = "citations",
      jdbcType = JdbcType.OTHER,
      typeHandler = PostgresTextTypeHandler.class)
  private String citationsJson;

  @TableField(
      value = "model_info",
      jdbcType = JdbcType.OTHER,
      typeHandler = PostgresTextTypeHandler.class)
  private String modelInfoJson;

  private String errorCode;
  private String errorMessage;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private OffsetDateTime completedAt;
}
