package com.hnu.backend.document.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
@TableName("documents")
public class Document {
  @TableId(type = IdType.INPUT)
  private UUID id;

  private UUID knowledgeBaseId;
  private String name;
  private UUID activeVersionId;
  private OffsetDateTime createdAt;
}
