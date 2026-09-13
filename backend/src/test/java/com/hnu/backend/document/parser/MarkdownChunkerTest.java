package com.hnu.backend.document.parser;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.configuration.RagProperties;
import org.junit.jupiter.api.Test;

class MarkdownChunkerTest {
  private MarkdownChunker chunker(int size, int overlap) {
    var config = new RagProperties();
    config.setChunkSize(size);
    config.setChunkMinSize(Math.max(1, size / 3));
    config.setChunkMaxSize(size + Math.max(1, size / 2));
    config.setChunkOverlap(overlap);
    return new MarkdownChunker(config);
  }

  @Test
  void keepsChineseHeadingsAndOriginalLineNumbers() {
    var pieces = chunker(800, 100).split("# 手册\r\n\r\n## 年假\r\n员工年假为五天。\r\n\r\n## 报销\r\n三天内提交。");
    assertEquals(1, pieces.size());
    assertEquals("手册", pieces.getFirst().heading());
    assertEquals(1, pieces.getFirst().lineStart());
    assertEquals(7, pieces.getFirst().lineEnd());
    assertTrue(pieces.getFirst().content().contains("## 年假\n员工年假为五天。"));
    assertTrue(pieces.getFirst().content().contains("## 报销\n三天内提交。"));
  }

  @Test
  void preservesFenceAndIgnoresHeadingsInsideIt() {
    String source = "# 配置\n\n```sh\n# 不是标题\n\necho hello\n```\n\n说明。";
    var pieces = chunker(800, 100).split(source);
    assertEquals(1, pieces.size());
    assertEquals(source, pieces.getFirst().content());
    assertEquals("配置", pieces.getFirst().heading());
    assertEquals(9, pieces.getFirst().lineEnd());
  }

  @Test
  void splitsLongParagraphWithOverlapWithoutLosingText() {
    String source = "甲乙丙丁".repeat(40);
    var pieces = chunker(40, 8).split(source);
    assertEquals(source.substring(32, 40), pieces.get(1).content().substring(0, 8));
    StringBuilder rebuilt = new StringBuilder(pieces.getFirst().content());
    for (int i = 1; i < pieces.size(); i++) rebuilt.append(pieces.get(i).content().substring(8));
    assertEquals(source, rebuilt.toString());
    assertTrue(
        pieces.stream()
            .allMatch(p -> p.content().length() <= 40 && p.lineStart() == 1 && p.lineEnd() == 1));
  }

  @Test
  void handlesEmptyUnclosedFenceAndEmoji() {
    assertTrue(chunker(40, 8).split("\n \n").isEmpty());
    var unclosed = chunker(40, 8).split("~~~\n# 代码\n内容");
    assertEquals("", unclosed.getFirst().heading());
    for (var piece : chunker(17, 3).split("😀".repeat(50))) {
      assertFalse(Character.isLowSurrogate(piece.content().charAt(0)));
      assertFalse(Character.isHighSurrogate(piece.content().charAt(piece.content().length() - 1)));
    }
  }

  @Test
  void packsShortSectionsAndSplitsLongTextAtSentenceBoundaries() {
    String shortSections =
        "# 手册\n\n"
            + java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(i -> "## 第" + i + "节\n这是第" + i + "节的简短说明。")
                .collect(java.util.stream.Collectors.joining("\n\n"));
    var packed = chunker(160, 20).split(shortSections);
    assertTrue(packed.size() < 8);
    assertTrue(packed.stream().allMatch(piece -> piece.content().length() <= 240));

    String longText = "第一句有完整含义。".repeat(30);
    var split = chunker(90, 15).split(longText);
    assertTrue(split.size() > 1);
    assertTrue(
        split.subList(0, split.size() - 1).stream().allMatch(p -> p.content().endsWith("。")));
  }
}
