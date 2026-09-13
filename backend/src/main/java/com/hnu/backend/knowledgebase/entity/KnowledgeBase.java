package com.hnu.backend.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
@TableName("knowledge_bases")
public class KnowledgeBase {
  @TableId(type = IdType.INPUT)
  private UUID id;

  private String name;
  private String embeddingModel;
  private Integer embeddingDimensions;
  private OffsetDateTime createdAt;

  @TableField(exist = false)
  private Long documentCount;
}
