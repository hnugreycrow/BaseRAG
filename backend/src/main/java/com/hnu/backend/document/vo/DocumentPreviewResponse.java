package com.hnu.backend.document.vo;

import java.util.List;

/** 文档当前原文件的在线预览数据。 */
public record DocumentPreviewResponse(
    String name,
    String format,
    String mediaType,
    long fileSizeBytes,
    String content,
    List<Block> blocks) {

  /** DOCX 的只读结构化内容块。 */
  public record Block(
      String kind,
      String content,
      Integer level,
      String sourceUnit,
      int sourceStart,
      int sourceEnd) {}
}
