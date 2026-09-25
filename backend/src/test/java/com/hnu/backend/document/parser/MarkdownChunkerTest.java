package com.hnu.backend.document.parser;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.configuration.RagProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    assertTrue(pieces.getFirst().content().contains("## 年假"));
    assertTrue(pieces.getFirst().content().contains("## 报销"));
    assertTrue(pieces.getFirst().embeddingText().contains("年假"));
  }

  @Test
  void preservesFenceAndIgnoresHeadingsInsideIt() {
    String source = "# 配置\n\n```sh\n# 不是标题\n\necho hello\n```\n\n说明。";
    var pieces = chunker(800, 100).split(source);
    assertEquals(1, pieces.size());
    assertTrue(pieces.getFirst().content().contains("```sh\n# 不是标题\n\necho hello\n```"));
    assertEquals("配置", pieces.getFirst().heading());
    assertEquals(1, pieces.getFirst().lineStart());
    assertEquals(9, pieces.getFirst().lineEnd());
  }

  @Test
  void splitsLongParagraphWithOverlapWithoutLosingText() {
    String source = "甲乙丙丁".repeat(40);
    var pieces = chunker(40, 8).split(source);
    assertEquals(source.substring(32, 40), pieces.get(1).content().substring(0, 8));
    StringBuilder rebuilt = new StringBuilder(pieces.getFirst().content());
    for (int i = 1; i < pieces.size(); i++) {
      rebuilt.append(pieces.get(i).content().substring(8));
    }
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
    assertTrue(
        packed.stream()
            .anyMatch(piece -> piece.content().contains("第1节") && piece.content().contains("第2节")));
    assertTrue(packed.stream().allMatch(piece -> piece.content().length() <= 240));

    String longText = "第一句有完整含义。".repeat(30);
    var split = chunker(90, 15).split(longText);
    assertTrue(split.size() > 1);
    assertTrue(
        split.subList(0, split.size() - 1).stream().allMatch(p -> p.content().endsWith("。")));
  }

  @Test
  void softHeadingsCanCombineShortPoliciesWithAccurateSources() {
    String source = "# 手册\n说明。\n\n## 年假\n年假五天。\n\n## 报销\n十天内报销。";
    var pieces = chunker(1400, 180).split(source);
    assertEquals(1, pieces.size());
    assertEquals("手册", pieces.getFirst().heading());
    assertEquals(1, pieces.getFirst().lineStart());
    assertEquals(8, pieces.getFirst().lineEnd());
    assertTrue(pieces.getFirst().content().contains("## 年假"));
    assertTrue(pieces.getFirst().content().contains("## 报销"));
  }

  @Test
  void tableRowsKeepHeadersAndColumnMeaning() {
    String source = "## 套餐\n\n| 名称 | 价格 |\n| --- | --- |\n| 基础版 | 100 |\n| 高级版 | 300 |";
    var pieces = chunker(50, 5).split(source);
    var rows = pieces.stream().filter(piece -> piece.content().contains("| 名称 | 价格 |")).toList();
    assertEquals(2, rows.size());
    assertTrue(rows.get(0).embeddingText().contains("名称: 基础版"));
    assertTrue(rows.get(0).embeddingText().contains("价格: 100"));
    assertTrue(rows.get(1).embeddingText().contains("名称: 高级版"));
    assertEquals(6, rows.get(1).lineStart());
  }

  @Test
  void longCodeRepeatsFenceAndSplitsOnWholeLines() {
    String source = "## 示例\n\n```sh\n" + "echo 1234567890\n".repeat(12) + "```";
    var pieces = chunker(50, 5).split(source);
    var code = pieces.stream().filter(piece -> piece.content().startsWith("```sh")).toList();
    assertTrue(code.size() > 1);
    assertTrue(code.stream().allMatch(piece -> piece.content().endsWith("```")));
    assertTrue(code.stream().allMatch(piece -> piece.content().contains("echo 1234567890")));
  }

  @Test
  void oversizedSingleLinesStayWithinMaximumAndKeepEmojiIntact() {
    String code = "```txt\n" + "😀".repeat(90) + "\n```";
    String table = "| 字段 | 值 |\n| --- | --- |\n| 编号 | " + "😀".repeat(90) + " |";
    for (String source : java.util.List.of(code, table)) {
      var pieces = chunker(50, 5).split(source);
      assertTrue(pieces.size() > 1);
      for (var piece : pieces) {
        assertTrue(piece.content().length() <= 75);
        assertFalse(Character.isLowSurrogate(piece.content().charAt(0)));
        assertFalse(
            Character.isHighSurrogate(piece.content().charAt(piece.content().length() - 1)));
      }
    }
  }

  @Test
  void oneColumnTableAndLongFenceMarkerAreRecognized() {
    var table = chunker(50, 5).split("| 名称 |\n| --- |\n| 基础版 |\n| 高级版 |");
    assertTrue(table.stream().anyMatch(piece -> piece.embeddingText().contains("名称: 基础版")));

    String code = "````java\n" + "println(1);\n".repeat(15) + "````";
    var pieces = chunker(50, 5).split(code);
    assertTrue(pieces.size() > 1);
    assertTrue(pieces.stream().allMatch(piece -> piece.content().endsWith("````")));
  }

  @Test
  void businessCorpusProducesBoundedTraceableChunks() throws IOException {
    Path corpus = Path.of("..", "evaluation", "datasets", "business-simulation");
    if (!Files.isDirectory(corpus)) {
      corpus = Path.of("evaluation", "datasets", "business-simulation");
    }
    try (var files = Files.list(corpus)) {
      var documents =
          files
              .filter(path -> path.getFileName().toString().matches("\\d{2}-.*\\.md"))
              .sorted()
              .toList();
      assertEquals(28, documents.size());
      var chunker = new MarkdownChunker(new RagProperties());
      for (Path document : documents) {
        String text = Files.readString(document);
        var pieces = chunker.split(text);
        assertFalse(pieces.isEmpty(), document.toString());
        assertTrue(pieces.size() <= 1000, document.toString());
        int lineCount = text.split("\\R", -1).length;
        for (var piece : pieces) {
          assertTrue(piece.content().length() <= 2000, document.toString());
          assertFalse(piece.embeddingText().isBlank(), document.toString());
          assertTrue(piece.lineStart() >= 1 && piece.lineEnd() <= lineCount, document.toString());
        }
      }
    }
  }

  @Test
  void fixedPhaseThreeDocumentHasFewSmallChunksWithoutExceedingBudget() throws IOException {
    Path document = Path.of("..", "docs", "phase-3-auth-observability-performance-ui-plan.md");
    String text = Files.readString(document);
    var pieces = new MarkdownChunker(new RagProperties()).split(text);
    assertFalse(pieces.isEmpty());
    assertTrue(pieces.size() <= 10);
    assertTrue(pieces.stream().filter(piece -> piece.content().length() < 500).count() <= 5);
    assertTrue(pieces.stream().allMatch(piece -> piece.content().length() <= 2000));
    assertEquals(1, pieces.getFirst().lineStart());
    assertTrue(pieces.getLast().lineEnd() >= 195);
  }

  @Test
  void joinsStructuresWithFollowingExplanations() {
    var chunker = chunker(1400, 180);
    for (String structure :
        java.util.List.of(
            "- 第一项。\n- 第二项。", "| 名称 | 值 |\n| --- | --- |\n| 测试 | 1 |", "```sh\necho hello\n```")) {
      String source = "## 配置\n" + structure + "\n\n后续说明：这些内容仅供测试。";
      var pieces = chunker.split(source);
      assertEquals(1, pieces.size(), structure);
      assertTrue(pieces.getFirst().content().contains(structure), structure);
      assertTrue(pieces.getFirst().content().contains("后续说明"), structure);
      assertEquals(source.split("\\R").length, pieces.getFirst().lineEnd(), structure);
    }
  }

  @Test
  void longListKeepsItemsAndItsIntroduction() {
    String source = "## 材料\n请准备以下材料：\n" + "- 第一个材料说明。\n".repeat(12);
    var pieces = chunker(55, 5).split(source);
    var lists = pieces.stream().filter(piece -> piece.content().contains("- 第一个材料")).toList();
    assertTrue(lists.size() > 1);
    assertTrue(lists.stream().allMatch(piece -> piece.embeddingText().contains("请准备以下材料")));
    assertTrue(lists.stream().allMatch(piece -> piece.content().contains("- 第一个材料")));
  }

  @Test
  void joinsShortIntroductionAndSoftNeighborHeading() {
    String source = "## 申请\n约束：\n\n- 必须实名。\n- 必须提交证明。\n\n## 例外\n可以代办。";
    var pieces = chunker(1400, 180).split(source);
    assertEquals(1, pieces.size());
    assertTrue(pieces.getFirst().content().contains("约束："));
    assertTrue(pieces.getFirst().content().contains("- 必须实名。"));
    assertEquals(
        pieces.getFirst().embeddingText().indexOf("约束："),
        pieces.getFirst().embeddingText().lastIndexOf("约束："));
    assertEquals(1, pieces.getFirst().lineStart());
    assertEquals(8, pieces.getFirst().lineEnd());
    assertTrue(pieces.getFirst().content().contains("## 例外"));
  }

  @Test
  void distantCaptionIsNotRepeatedIntoSplitListContinuation() {
    String source = "## 条件\n预告：\n\n\n\n\n" + "- 另一个主题的条目。\n".repeat(20);
    var pieces = chunker(50, 5).split(source);
    var continuation = pieces.stream().filter(piece -> piece.lineStart() > 7).toList();
    assertFalse(continuation.isEmpty());
    assertTrue(continuation.stream().allMatch(piece -> !piece.embeddingText().contains("预告：")));
  }

  @Test
  void joinsShortTableAndCodeCaptionsWithActualSourceLines() {
    String table = "## 套餐\n说明：\n\n| 名称 | 价格 |\n| --- | --- |\n| 基础版 | 100 |";
    var tablePieces = chunker(1400, 180).split(table);
    assertEquals(1, tablePieces.size());
    assertTrue(tablePieces.getFirst().content().contains("说明："));
    assertTrue(tablePieces.getFirst().content().contains("| 名称 | 价格 |"));
    assertTrue(tablePieces.getFirst().embeddingText().contains("名称: 基础版"));
    assertEquals(1, tablePieces.getFirst().lineStart());
    assertEquals(6, tablePieces.getFirst().lineEnd());

    String code = "## 示例\n命令：\n\n```sh\necho hello\n```";
    var codePieces = chunker(1400, 180).split(code);
    assertEquals(1, codePieces.size());
    assertTrue(codePieces.getFirst().content().contains("命令："));
    assertTrue(codePieces.getFirst().content().contains("```sh"));
    assertEquals(1, codePieces.getFirst().lineStart());
    assertEquals(6, codePieces.getFirst().lineEnd());
  }

  @Test
  void repeatedListIntroductionIsIndexContextNotFalseSource() {
    String source = "## 材料\n请准备：\n\n" + ("- 材料编号".repeat(10) + "。\n").repeat(8);
    var pieces = chunker(100, 10).split(source);
    var continuation = pieces.stream().filter(piece -> piece.lineStart() > 4).toList();
    assertFalse(continuation.isEmpty());
    assertTrue(continuation.stream().allMatch(piece -> piece.embeddingText().contains("请准备：")));
    assertTrue(continuation.stream().allMatch(piece -> !piece.content().contains("请准备：")));
  }

  @Test
  void oversizedListItemFallsBackWithoutMechanicalOverlap() {
    String source = "- " + "甲乙".repeat(100);
    var pieces = chunker(40, 8).split(source);
    assertTrue(pieces.size() > 1);
    assertEquals(
        source,
        pieces.stream()
            .map(MarkdownChunker.Piece::content)
            .collect(java.util.stream.Collectors.joining()));
    assertTrue(pieces.stream().allMatch(piece -> piece.content().length() <= 60));
  }
}
