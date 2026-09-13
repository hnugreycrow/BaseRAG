package com.hnu.backend.document.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
@TableName("document_versions")
public class DocumentVersion {
  @TableId(type = IdType.INPUT)
  private UUID id;

  private UUID documentId;
  private UUID knowledgeBaseId;
  private String fileHash;
  private String storageKey;
  private String status;
  private String errorCode;
  private String parserVersion;
  private String chunkerVersion;
  private String embeddingModel;
  private Integer embeddingDimensions;
  private OffsetDateTime createdAt;
}
