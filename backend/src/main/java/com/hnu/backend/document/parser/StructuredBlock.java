package com.hnu.backend.document.parser;

import java.util.List;

/** 与文件格式无关的解析结果；展示内容、向量上下文和真实来源范围分别保存，piece 标记原子块切出的片段。 */
public record StructuredBlock(
    Kind kind,
    int section,
    String heading,
    List<String> outlinePath,
    String content,
    String indexText,
    SourceSpan source,
    boolean piece) {
  /** 标题路径按层级保存，避免跨标题合并时仅靠字符串推断公共祖先。 */
  public StructuredBlock {
    outlinePath = List.copyOf(outlinePath);
  }

  public StructuredBlock(
      Kind kind, int section, String heading, String content, String indexText, SourceSpan source) {
    this(
        kind,
        section,
        heading,
        heading.isBlank() ? List.of() : List.of(heading.split(" / ")),
        content,
        indexText,
        source,
        false);
  }

  public enum Kind {
    HEADING,
    TEXT,
    CODE,
    TABLE,
    LIST
  }

  /** 位置单位由解析器指定；偏移量用于判定相邻顺序及阻止重叠片段回并。 */
  public record SourceSpan(Unit unit, int start, int end, int startOffset, int endOffset) {
    public enum Unit {
      LINE,
      PAGE,
      PARAGRAPH
    }
  }
}
