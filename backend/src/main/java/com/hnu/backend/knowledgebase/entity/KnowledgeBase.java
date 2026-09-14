package com.hnu.backend.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

/** 知识库实体及其固定的向量模型绑定。 */
@Data
@TableName("knowledge_bases")
public class KnowledgeBase {
  /** 知识库标识。 */
  @TableId(type = IdType.INPUT)
  private UUID id;

  /** 知识库名称。 */
  private String name;

  /** 建库时绑定的向量模型名称。 */
  private String embeddingModel;

  /** 建库时绑定的向量维度。 */
  private Integer embeddingDimensions;

  /** 创建时间。 */
  private OffsetDateTime createdAt;

  /** 列表查询动态计算的文档数量，不映射到表字段。 */
  @TableField(exist = false)
  private Long documentCount;
}
