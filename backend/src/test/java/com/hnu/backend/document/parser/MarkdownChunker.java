package com.hnu.backend.document.parser;

import com.hnu.backend.document.config.DocumentParsingOptions;
import com.hnu.backend.rag.config.RagProperties;
import java.util.List;

/**
 * 将 Markdown 标题、正文和结构单元解析为原子块，再按软标题与长度预算合并。
 *
 * <p>切分结果保留标题路径和原文行号，便于回答引用回溯到原文。
 */
public class MarkdownChunker {
  /**
   * 旧版 Markdown 测试使用的分块投影。
   *
   * @param content 展示正文
   * @param embeddingText 向量化正文
   * @param heading 所属标题
   * @param lineStart 原文起始行
   * @param lineEnd 原文结束行
   */
  public record Piece(
      String content, String embeddingText, String heading, int lineStart, int lineEnd) {}

  private final MarkdownStructureParser parser;
  private final StructuredChunkPacker packer;
  private final int maxSize;

  /** 组合新解析与打包能力，供原有 Markdown 行为测试使用。 */
  public MarkdownChunker(RagProperties config) {
    var options =
        new DocumentParsingOptions(
            config.getChunkSize(),
            config.getChunkMinSize(),
            config.getChunkMaxSize(),
            config.getChunkOverlap());
    parser = new MarkdownStructureParser(options);
    packer = new StructuredChunkPacker(options);
    maxSize = Math.max(options.chunkSize(), options.chunkMaxSize());
  }

  /** 返回解析结构块的最大长度。 */
  public int maxSize() {
    return maxSize;
  }

  /** 提取 Markdown 结构块。 */
  public List<StructuredBlock> parse(String text) {
    return parser.parse(text);
  }

  /** 打包通用结构块。 */
  public List<DocumentChunkResult> pack(List<StructuredBlock> blocks) {
    return packer.pack(blocks);
  }

  /** 返回原有行号形式的 Markdown 分块。 */
  public List<Piece> split(String text) {
    return pack(parse(text)).stream()
        .map(
            c ->
                new Piece(
                    c.content(),
                    c.embeddingText(),
                    c.heading(),
                    c.source().start(),
                    c.source().end()))
        .toList();
  }
}
