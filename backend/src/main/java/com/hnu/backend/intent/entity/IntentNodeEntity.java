package com.hnu.backend.intent.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;
import lombok.Data;

/** MyBatis-Plus 意图节点实体。 */
@Data
@TableName("intent_nodes")
public class IntentNodeEntity {
  @TableId(type = IdType.INPUT)
  private UUID id;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private UUID parentId;

  private String name;
  private String description;

  @TableField("examples_json")
  private String examplesJson;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String kind;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String toolName;

  private Boolean enabled;
  private Integer sortOrder;
}
