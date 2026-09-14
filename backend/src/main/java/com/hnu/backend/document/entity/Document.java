package com.hnu.backend.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

/** 文档逻辑实体；具体文件和处理状态由文档版本实体承载。 */
@Data
@TableName("documents")
public class Document {
  /** 文档标识。 */
  @TableId(type = IdType.INPUT)
  private UUID id;

  /** 所属知识库标识。 */
  private UUID knowledgeBaseId;

  /** 展示名称。 */
  private String name;

  /** 当前对外生效的文档版本标识。 */
  private UUID activeVersionId;

  /** 创建时间。 */
  private OffsetDateTime createdAt;
}
