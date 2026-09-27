package com.hnu.backend.document.parser;

import java.util.List;

/**
 * 与文件格式无关的解析结果；展示内容、向量上下文和真实来源范围分别保存。
 *
 * @param kind 原始内容类型
 * @param section 所属章节序号
 * @param heading 所属标题文本
 * @param outlinePath 从上级到当前级的标题路径
 * @param content 原文展示内容
 * @param indexText 用于分块或向量索引的文本
 * @param source 原文件中的位置范围
 * @param piece 是否为原子块切出的片段
 */
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

  /**
   * 兼容只提供平面标题路径的解析器，默认该块未被切分。
   *
   * @param kind 原始内容类型
   * @param section 所属章节序号
   * @param heading 使用 {@code " / "} 连接的标题路径
   * @param content 原文展示内容
   * @param indexText 用于索引的文本
   * @param source 原文件中的位置范围
   */
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

  /** 原文件解析后的结构类型，用于控制标题、代码和表格的分块策略。 */
  public enum Kind {
    HEADING,
    TEXT,
    CODE,
    TABLE,
    LIST
  }

  /**
   * 原文件位置及解析器内部偏移；偏移量用于判定相邻顺序并阻止重叠片段回并。
   *
   * @param unit 位置单位，由具体文件解析器决定
   * @param start 原文件中的起始行、页或段落，包含该位置
   * @param end 原文件中的结束行、页或段落，包含该位置
   * @param startOffset 解析文本中的起始 UTF-16 偏移，包含该位置
   * @param endOffset 解析文本中的结束 UTF-16 偏移，不包含该位置
   */
  public record SourceSpan(Unit unit, int start, int end, int startOffset, int endOffset) {
    /** 来源范围的定位单位，由 Markdown、PDF 或 DOCX 解析器确定。 */
    public enum Unit {
      LINE,
      PAGE,
      PARAGRAPH
    }
  }
}
