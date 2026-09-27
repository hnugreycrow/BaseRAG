package com.hnu.backend.document.parser;

import java.util.List;

/** 保留页码与段落位置的结构元素切分工具。 */
final class StructuredBlocks {
  private StructuredBlocks() {}

  /** 将过长的单个文本元素拆成有界结构块，来源位置保持原页或原段落。 */
  static void append(
      List<StructuredBlock> blocks,
      StructuredBlock.Kind kind,
      int section,
      String heading,
      List<String> path,
      String text,
      StructuredBlock.SourceSpan.Unit unit,
      int position,
      int offset,
      int maxSize) {
    // 防止单个 PDF 页或 DOCX 段落超过后续打包器的最大块长度。
    int budget = Math.max(1, maxSize);
    for (int start = 0; start < text.length(); start += budget) {
      int end = Math.min(text.length(), start + budget);
      String part = text.substring(start, end);
      blocks.add(
          new StructuredBlock(
              kind,
              section,
              heading,
              path,
              part,
              part,
              new StructuredBlock.SourceSpan(
                  unit, position, position, offset + start, offset + end),
              text.length() > budget));
    }
  }
}
