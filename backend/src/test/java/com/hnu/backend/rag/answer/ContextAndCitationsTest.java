package com.hnu.backend.rag.answer;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.rag.configuration.RagProperties;
import com.hnu.backend.rag.retrieval.SearchHit;
import java.util.*;
import org.junit.jupiter.api.Test;

public class ContextAndCitationsTest {
  public static SearchHit hit(String content) {
    SearchHit hit = new SearchHit();
    hit.setKnowledgeBaseId(UUID.randomUUID());
    hit.setKnowledgeBaseName("员工制度");
    hit.setChunkId(UUID.randomUUID());
    hit.setDocumentId(UUID.randomUUID());
    hit.setVersionId(UUID.randomUUID());
    hit.setDocumentName("手册.md");
    hit.setHeading("规则");
    hit.setLineStart(1);
    hit.setLineEnd(2);
    hit.setSimilarity(.9);
    hit.setContent(content);
    return hit;
  }

  @Test
  void sendsAllWholeTopKChunksWithoutApplicationBudgetAndDeduplicates() {
    var config = new RagProperties();
    var first = hit("甲".repeat(200));
    var second = hit("乙".repeat(800));
    var context = new ContextBuilder(config).build(List.of(first, first, second));
    assertEquals(2, context.sources().size());
    assertEquals(first.getContent(), context.sources().getFirst().content());
    assertTrue(context.text().contains(first.getContent()));
    assertTrue(context.text().contains(second.getContent()));
    assertTrue(context.text().contains("<content ref=\"S1\">"));
    assertFalse(context.text().contains(first.getDocumentName()));
  }

  @Test
  void groupsSelectedChunksAfterTopKAndKeepsPrimaryRankAndDocumentOrder() {
    var first = hit("后段");
    first.setChunkIndex(3);
    var other = hit("其他文档");
    var earlier = hit("前段");
    earlier.setDocumentId(first.getDocumentId());
    earlier.setVersionId(first.getVersionId());
    earlier.setChunkIndex(1);
    var adjacent = hit("相邻");
    adjacent.setDocumentId(first.getDocumentId());
    adjacent.setVersionId(first.getVersionId());
    adjacent.setChunkIndex(2);
    var context =
        new ContextBuilder(new RagProperties())
            .build(List.of(first, other, earlier, adjacent, first));
    assertEquals(List.of("S1", "S2"), context.sources().stream().map(s -> s.citationId()).toList());
    var source = context.sources().getFirst();
    assertEquals(2, source.schemaVersion());
    assertEquals(first.getChunkId(), source.primaryLocation().chunkId());
    assertEquals(
        List.of(earlier.getChunkId(), adjacent.getChunkId(), first.getChunkId()),
        source.locations().stream().map(s -> s.chunkId()).toList());
    assertEquals("前段\n相邻\n后段", source.content());
    assertEquals("LINE", source.locations().getFirst().range().unit());
    assertEquals("MARKDOWN", source.format());
    assertEquals(other.getDocumentId(), context.sources().get(1).documentId());

    adjacent.setChunkIndex(7);
    assertEquals(
        "前段\n\n—— 中间内容省略 ——\n\n后段\n\n—— 中间内容省略 ——\n\n相邻",
        new ContextBuilder(new RagProperties())
            .build(List.of(first, earlier, adjacent))
            .sources()
            .getFirst()
            .content());
  }

  @Test
  void preservesPdfAndDocxSourceUnitsInAnswerSnapshots() {
    var pdf = hit("PDF evidence");
    pdf.setFormat("PDF");
    pdf.setSourceUnit("PAGE");
    pdf.setLineStart(3);
    pdf.setLineEnd(4);
    var docx = hit("DOCX evidence");
    docx.setFormat("DOCX");
    docx.setSourceUnit("PARAGRAPH");
    docx.setLineStart(7);
    docx.setLineEnd(7);
    var sources = new ContextBuilder(new RagProperties()).build(List.of(pdf, docx)).sources();
    assertEquals("PDF", sources.get(0).format());
    assertEquals("PAGE", sources.get(0).primaryLocation().range().unit());
    assertEquals("第 3–4 页", sources.get(0).primaryLocation().range().label());
    assertEquals("DOCX", sources.get(1).format());
    assertEquals("PARAGRAPH", sources.get(1).primaryLocation().range().unit());
    assertEquals("第 7 段", sources.get(1).primaryLocation().range().label());
  }

  @Test
  void keepsDifferentVersionsIndependent() {
    var first = hit("旧");
    var next = hit("新");
    next.setDocumentId(first.getDocumentId());
    var sources = new ContextBuilder(new RagProperties()).build(List.of(first, next)).sources();
    assertEquals(2, sources.size());
    assertEquals(List.of("S1", "S2"), sources.stream().map(s -> s.citationId()).toList());
  }

  @Test
  void acceptsOnlyProvidedCitationIds() {
    var context = new ContextBuilder(new RagProperties()).build(List.of(hit("依据")));
    assertEquals(List.of("S1"), Citations.validate("答案 [S1][S1]", context.sources()));
    assertEquals(List.of(), Citations.validate("现有资料不足", context.sources()));
    for (String answer : List.of("答案 [S99]", "[S1,S2]", "[s1]", "[S01]")) {
      assertThrows(
          IllegalArgumentException.class, () -> Citations.validate(answer, context.sources()));
    }
  }

  @Test
  void validatesKnowledgeAndToolReferencesIndependently() {
    var context = new ContextBuilder(new RagProperties()).build(List.of(hit("依据")));

    Citations.Validation result =
        Citations.validate("答案 [S1][S1]，根据工具 T1 和 T1。", context.sources(), List.of("T1"));

    assertEquals(List.of("S1"), result.citations());
    assertEquals(List.of("T1"), result.toolReferences());
    assertThrows(
        IllegalArgumentException.class,
        () -> Citations.validate("答案 [S99]，工具 T1。", context.sources(), List.of("T1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> Citations.validate("答案 [S1]，工具 T99。", context.sources(), List.of("T1")));
    assertEquals(
        List.of(),
        Citations.validate("T1 加权并不是工具引用。", context.sources(), List.of()).toolReferences());
  }

  @Test
  void normalizesParagraphsAndListItemsButLeavesFencedCodeAndToolsAlone() {
    var answer =
        "事实 [S1] 和补充 [S1][S2]。\n\n- 第一项 [S1]\n  续行 [S1] 工具 T1。\n- 第二项 [S2]\n\n```md\n[S99] 工具 T99\n```";
    var context = new ContextBuilder(new RagProperties()).build(List.of(hit("甲"), hit("乙")));
    assertEquals(
        List.of("S1", "S2"),
        Citations.validate(answer, context.sources(), List.of("T1")).citations());
    assertEquals(
        "事实  和补充。 [S1][S2]\n\n- 第一项\n  续行  工具 T1。 [S1]\n- 第二项 [S2]\n\n```md\n[S99] 工具 T99\n```",
        Citations.normalize(answer));
    assertEquals(Citations.normalize(answer), Citations.normalize(Citations.normalize(answer)));
  }
}
