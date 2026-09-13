package com.hnu.backend.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hnu.backend.shared.persistence.PostgresTextTypeHandler;
import java.util.UUID;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

@Data
@TableName(value = "document_chunks", autoResultMap = true)
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

  @TableField(
      value = "embedding",
      jdbcType = JdbcType.OTHER,
      typeHandler = PostgresTextTypeHandler.class)
  private String vector;
}
