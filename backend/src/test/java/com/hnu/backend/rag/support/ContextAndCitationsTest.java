package com.hnu.backend.rag.support;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.rag.model.SearchHit;
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
}
