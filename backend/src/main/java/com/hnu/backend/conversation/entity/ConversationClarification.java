package com.hnu.backend.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;
import lombok.Data;

/** 会话 KB 澄清待办；只保存规划、意图选择和消息引用。 */
@Data
@TableName("conversation_clarifications")
public class ConversationClarification {
  /** 服务端签发的待办标识。 */
  @TableId(type = IdType.INPUT)
  private UUID id;

  /** 所属私有会话。 */
  private UUID conversationId;

  /** 原问题消息引用。 */
  private UUID originalMessageId;

  /** 当前追问消息引用。 */
  private UUID assistantMessageId;

  /** 最近一次补充消息引用。 */
  private UUID resumeMessageId;

  /** 持有本待办的回答生成标识。 */
  private UUID generationId;

  /** 不含执行结果和工具参数的恢复上下文。 */
  private String contextJson;

  /** PENDING、RESUMING、RESOLVED 或 CANCELLED。 */
  private String status;
}
