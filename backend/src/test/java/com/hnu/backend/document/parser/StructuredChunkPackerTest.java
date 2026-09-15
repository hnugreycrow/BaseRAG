package com.hnu.backend.document.parser;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.document.parser.StructuredBlock.Kind;
import com.hnu.backend.document.parser.StructuredBlock.SourceSpan;
import com.hnu.backend.document.parser.StructuredBlock.SourceSpan.Unit;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredChunkPackerTest {
  private StructuredChunkPacker packer(int target, int minimum, int maximum) {
    var config = new RagProperties();
    config.setChunkSize(target);
    config.setChunkMinSize(minimum);
    config.setChunkMaxSize(maximum);
    return new StructuredChunkPacker(config);
  }

  @Test
  void acceptsPageLocatedElementsWithoutMarkdownParser() {
    var config = new RagProperties();
    var packer = new StructuredChunkPacker(config);
    var blocks =
        List.of(
            new StructuredBlock(
                Kind.TEXT, 1, "制度 / 条件", "约束：", "约束：", new SourceSpan(Unit.PAGE, 2, 2, 0, 3)),
            new StructuredBlock(
                Kind.LIST,
                1,
                "制度 / 条件",
                "- 年龄至少十八岁。",
                "- 年龄至少十八岁。",
                new SourceSpan(Unit.PAGE, 2, 3, 4, 15)),
            new StructuredBlock(
                Kind.TEXT,
                2,
                "制度 / 例外",
                "未成年人由监护人申请。",
                "未成年人由监护人申请。",
                new SourceSpan(Unit.PAGE, 4, 4, 16, 29)));
    var chunks = packer.pack(blocks);
    assertEquals(1, chunks.size());
    assertTrue(chunks.getFirst().content().contains("约束："));
    assertTrue(chunks.getFirst().content().contains("- 年龄"));
    assertEquals(Unit.PAGE, chunks.getFirst().source().unit());
    assertEquals(2, chunks.getFirst().source().start());
    assertEquals(4, chunks.getFirst().source().end());
    assertEquals("制度", chunks.getFirst().heading());
    assertTrue(chunks.getFirst().content().contains("未成年人"));
  }

  @Test
  void shortTailMergesBackwardAcrossSoftHeading() {
    var blocks =
        List.of(
            new StructuredBlock(
                Kind.TEXT,
                1,
                "总则 / 一",
                "甲".repeat(38),
                "甲".repeat(38),
                new SourceSpan(Unit.LINE, 1, 1, 0, 38)),
            new StructuredBlock(
                Kind.TEXT,
                2,
                "总则 / 二",
                "乙".repeat(35),
                "乙".repeat(35),
                new SourceSpan(Unit.LINE, 2, 2, 38, 73)),
            new StructuredBlock(
                Kind.TEXT,
                3,
                "总则 / 三",
                "丙".repeat(8),
                "丙".repeat(8),
                new SourceSpan(Unit.LINE, 3, 3, 73, 81)));
    var chunks = packer(40, 20, 60).pack(blocks);
    assertEquals(2, chunks.size());
    assertEquals("总则", chunks.get(1).heading());
    assertTrue(chunks.get(1).content().contains("乙".repeat(35)));
    assertTrue(chunks.get(1).content().contains("丙".repeat(8)));
    assertEquals(2, chunks.get(1).source().start());
    assertEquals(3, chunks.get(1).source().end());
  }

  @Test
  void parserPiecesDoNotRejoinEvenWhenTheirBudgetFits() {
    var pieces =
        List.of(
            new StructuredBlock(
                Kind.TEXT,
                1,
                "段落",
                List.of("段落"),
                "第一段重叠文本",
                "第一段重叠文本",
                new SourceSpan(Unit.LINE, 1, 1, 0, 8),
                true),
            new StructuredBlock(
                Kind.TEXT,
                1,
                "段落",
                List.of("段落"),
                "重叠文本第二段",
                "重叠文本第二段",
                new SourceSpan(Unit.LINE, 1, 1, 4, 12),
                true));
    var chunks = packer(1400, 500, 2000).pack(pieces);
    assertEquals(2, chunks.size());
    assertEquals("第一段重叠文本", chunks.getFirst().content());
    assertEquals("重叠文本第二段", chunks.getLast().content());
  }
}
