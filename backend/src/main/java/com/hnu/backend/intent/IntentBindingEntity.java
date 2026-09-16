package com.hnu.backend.intent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;
import lombok.Data;

/** 意图叶子与公共知识库的关联实体。 */
@Data
@TableName("intent_node_knowledge_bases")
public class IntentBindingEntity {
  @TableId(type = IdType.INPUT)
  private UUID id;

  private UUID nodeId;
  private UUID knowledgeBaseId;
}
