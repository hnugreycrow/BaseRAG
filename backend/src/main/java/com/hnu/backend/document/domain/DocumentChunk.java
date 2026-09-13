package com.hnu.backend.document.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;
import lombok.Data;

@Data
@TableName("document_chunks")
public class DocumentChunk {
  @TableId(type = IdType.INPUT)
  private UUID id;

  private UUID documentId;
  private UUID versionId;
  private Integer chunkIndex;
  private String content;
  private String heading;
  private Integer lineStart;
  private Integer lineEnd;
  private Integer embeddingDimensions;

  @TableField(exist = false)
  private String vector;
}
