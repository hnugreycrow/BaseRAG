package com.hnu.backend.rag.retrieval;

import java.util.UUID;
import lombok.Data;

/** 检索 SQL 返回的候选分块及其来源信息。 */
@Data
public class SearchHit {
  /** 知识库标识。 */
  private UUID knowledgeBaseId;

  /** 知识库名称。 */
  private String knowledgeBaseName;

  /** 分块标识。 */
  private UUID chunkId;

  /** 文档标识。 */
  private UUID documentId;

  /** 文档版本标识。 */
  private UUID versionId;

  /** 文档名称。 */
  private String documentName;

  /** 分块在文档中的顺序。 */
  private int chunkIndex;

  /** 分块正文。 */
  private String content;

  /** 分块所属标题。 */
  private String heading;

  /** 原文起始行号。 */
  private int lineStart;

  /** 原文结束行号。 */
  private int lineEnd;

  /** 查询向量与分块向量的相似度。 */
  private double similarity;
}
