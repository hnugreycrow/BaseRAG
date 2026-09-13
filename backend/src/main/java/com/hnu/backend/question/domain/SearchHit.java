package com.hnu.backend.question.domain;

import java.util.UUID;
import lombok.Data;

@Data
public class SearchHit {
  private UUID knowledgeBaseId;
  private String knowledgeBaseName;
  private UUID chunkId;
  private UUID documentId;
  private UUID versionId;
  private String documentName;
  private String content;
  private String heading;
  private int lineStart;
  private int lineEnd;
  private double similarity;
}
