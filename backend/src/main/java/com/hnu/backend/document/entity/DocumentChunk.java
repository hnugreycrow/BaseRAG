package com.hnu.backend.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hnu.backend.common.persistence.PostgresTextTypeHandler;
import java.util.UUID;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

/** 从某个文档版本切分出的可检索文本块。 */
@Data
@TableName(value = "document_chunks", autoResultMap = true)
public class DocumentChunk {
  /** 分块标识。 */
  @TableId(type = IdType.INPUT)
  private UUID id;

  /** 所属文档标识。 */
  private UUID documentId;

  /** 所属文档版本标识。 */
  private UUID versionId;

  /** 分块在文档内的顺序。 */
  private Integer chunkIndex;

  /** 分块正文。 */
  private String content;

  /** 入库时用于向量化的文本，可包含原文之外的章节上下文。 */
  private String embeddingText;

  /** 分块所属的标题路径。 */
  private String heading;

  /** 旧版 Markdown 行号字段；PDF 和 DOCX 分块为空。 */
  private Integer lineStart;

  /** 旧版 Markdown 结束行号字段；PDF 和 DOCX 分块为空。 */
  private Integer lineEnd;

  /** 来源位置单位：行、页或段落。 */
  private String sourceUnit;

  /** 分块在原文件中的起始位置，按来源单位计数。 */
  private Integer sourceStart;

  /** 分块在原文件中的结束位置，按来源单位计数。 */
  private Integer sourceEnd;

  /** 向量维度，用于确保查询向量与存储向量兼容。 */
  private Integer embeddingDimensions;

  /** pgvector 向量的文本表示，由类型处理器写入数据库专用列。 */
  @TableField(
      value = "embedding",
      jdbcType = JdbcType.OTHER,
      typeHandler = PostgresTextTypeHandler.class)
  private String vector;
}
