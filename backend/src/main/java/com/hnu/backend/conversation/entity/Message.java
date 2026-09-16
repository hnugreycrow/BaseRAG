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

/** 会话消息实体，同时承载助手回答的版本、状态和检索元数据。 */
@Data
@TableName(value = "messages", autoResultMap = true)
public class Message {
  /** 消息标识。 */
  @TableId(type = IdType.INPUT)
  private UUID id;

  /** 所属会话标识。 */
  private UUID conversationId;

  /** 客户端幂等请求标识。 */
  private UUID clientRequestId;

  /** 消息角色，取值为 USER 或 ASSISTANT。 */
  private MessageRole role;

  /** 会话中的轮次序号。 */
  private int turnIndex;

  /** 同一用户消息下的助手回答版本序号。 */
  private int variantIndex;

  /** 是否为当前展示和记忆使用的回答版本。 */
  private boolean active;

  /** 助手消息所回复的用户消息标识。 */
  private UUID replyToId;

  /** 消息生成状态。 */
  private MessageStatus status;

  /** 消息正文或流式生成的已保存部分。 */
  private String content;

  /** 创建回答版本时固定的深度思考选择。 */
  private boolean thinkingEnabled;

  /** 模型返回的思考内容，与最终回答正文分开保存。 */
  private String reasoningContent;

  /** 规划阶段生成并实际用于检索的问题。 */
  private String retrievalQuery;

  /** 检索来源列表的 JSON 表示。 */
  @TableField(
      value = "sources",
      jdbcType = JdbcType.OTHER,
      typeHandler = PostgresTextTypeHandler.class)
  private String sourcesJson;

  /** 回答实际引用标识列表的 JSON 表示。 */
  @TableField(
      value = "citations",
      jdbcType = JdbcType.OTHER,
      typeHandler = PostgresTextTypeHandler.class)
  private String citationsJson;

  /** 实际生成模型信息的 JSON 表示。 */
  @TableField(
      value = "model_info",
      jdbcType = JdbcType.OTHER,
      typeHandler = PostgresTextTypeHandler.class)
  private String modelInfoJson;

  /** 失败或取消时的稳定错误码。 */
  private String errorCode;

  /** 失败或取消时的错误信息。 */
  private String errorMessage;

  /** 创建时间。 */
  private OffsetDateTime createdAt;

  /** 最近更新时间或检查点时间。 */
  private OffsetDateTime updatedAt;

  /** 消息进入完成、失败或取消终态的时间。 */
  private OffsetDateTime completedAt;
}
