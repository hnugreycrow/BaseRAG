package com.hnu.backend.document.vo;

import java.util.List;

/**
 * 文档当前原文件的在线预览数据；结构化块仅用于 DOCX 展示。
 *
 * @param name 原文件名
 * @param format 服务端识别的原文件格式
 * @param mediaType 原文件的 MIME 类型
 * @param fileSizeBytes 原文件大小，单位字节；历史版本缺失大小时为 0
 * @param content Markdown 原文；PDF 和 DOCX 时为 {@code null}
 * @param blocks DOCX 的结构化内容块；其他格式为空列表
 */
public record DocumentPreviewResponse(
    String name,
    String format,
    String mediaType,
    long fileSizeBytes,
    String content,
    List<Block> blocks) {

  /**
   * DOCX 的只读结构化内容块，来源位置按段落解释。
   *
   * @param kind 段落或表格等内容类型
   * @param content 展示文本
   * @param level 标题层级；非标题可为空
   * @param sourceUnit 来源位置单位
   * @param sourceStart 原文件起始位置，包含该位置
   * @param sourceEnd 原文件结束位置，包含该位置
   */
  public record Block(
      String kind,
      String content,
      Integer level,
      String sourceUnit,
      int sourceStart,
      int sourceEnd) {}
}
