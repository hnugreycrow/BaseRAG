package com.hnu.backend.rag.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hnu.backend.rag.config.RagProperties;
import com.hnu.backend.rag.pipeline.RagBudgetSnapshot;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DeduplicationStageTest {
  private final DeduplicationStage stage = new DeduplicationStage();
  private final RagBudgetSnapshot budget = RagBudgetSnapshot.from(new RagProperties());

  @Test
  void mergesSameChunkAndKeepsEveryQuestionAndAttribution() {
    UUID chunkId = id(1);
    UUID documentId = id(100);
    UUID versionId = id(200);
    EvidenceCandidate first = candidate(chunkId, documentId, versionId, 0, "正文甲", "Q1", .10);
    EvidenceCandidate second = candidate(chunkId, documentId, versionId, 0, "正文甲", "Q2", .08);

    DeduplicationResult result = stage.execute(List.of(first, second), budget);

    assertEquals(1, result.candidates().size());
    assertEquals(1, result.exactChunkMerged());
    assertEquals(Set.of("Q1", "Q2"), result.candidates().getFirst().sourceSubQuestionIds());
    assertEquals(2, result.candidates().getFirst().attributions().size());
    assertEquals(.10, result.candidates().getFirst().fusionScore());
  }

  @Test
  void mergesNormalizedContentAcrossDocumentsAndKeepsBothSources() {
    EvidenceCandidate first = candidate(id(1), id(100), id(200), 0, "Ａ  B\nC", "Q1", .05);
    EvidenceCandidate second = candidate(id(2), id(101), id(201), 0, "a b c", "Q2", .09);

    DeduplicationResult result = stage.execute(List.of(first, second), budget);

    assertEquals(1, result.candidates().size());
    assertEquals(1, result.normalizedContentMerged());
    assertEquals(id(2), result.candidates().getFirst().candidateId());
    assertEquals(2, result.candidates().getFirst().sources().size());
  }

  @Test
  void mergesHighlyOverlappingAdjacentChunksButNotUnrelatedLocations() {
    String base = "员工申请年假需要提前提交审批并由直属负责人确认剩余额度和交接安排。";
    EvidenceCandidate adjacentFirst =
        candidate(id(1), id(100), id(200), 0, base + "第一段补充。", "Q1", .10);
    EvidenceCandidate adjacentSecond =
        candidate(id(2), id(100), id(200), 1, base + "第二段补充。", "Q1", .09);
    EvidenceCandidate nonAdjacent =
        candidate(id(3), id(100), id(200), 3, base + "第三段补充。", "Q1", .08);
    EvidenceCandidate otherDocument =
        candidate(id(4), id(101), id(201), 1, base + "第四段补充。", "Q1", .07);

    DeduplicationResult result =
        stage.execute(List.of(adjacentFirst, adjacentSecond, nonAdjacent, otherDocument), budget);

    assertEquals(3, result.candidates().size());
    assertEquals(1, result.adjacentOverlapMerged());
  }

  @Test
  void doesNotMergeBlankContents() {
    EvidenceCandidate first = candidate(id(1), id(100), id(200), 0, "", "Q1", .10);
    EvidenceCandidate second = candidate(id(2), id(101), id(201), 0, "  ", "Q1", .09);

    DeduplicationResult result = stage.execute(List.of(first, second), budget);

    assertEquals(2, result.candidates().size());
    assertEquals(0, result.normalizedContentMerged());
  }

  @Test
  void mergesTransitiveAdjacentOverlapGroups() {
    String common =
        IntStream.range(0, 40)
            .mapToObj(index -> "制度第" + index + "项要求申请人核对额度并完成审批。")
            .collect(Collectors.joining());
    EvidenceCandidate first = candidate(id(1), id(100), id(200), 0, common + "左侧说明甲。", "Q1", .10);
    EvidenceCandidate middle = candidate(id(2), id(100), id(200), 1, common + "中间说明乙。", "Q1", .09);
    EvidenceCandidate last = candidate(id(3), id(100), id(200), 2, common + "右侧说明丙。", "Q1", .08);

    DeduplicationResult result = stage.execute(List.of(first, middle, last), budget);

    assertEquals(1, result.candidates().size());
    assertEquals(2, result.adjacentOverlapMerged());
    assertEquals(3, result.candidates().getFirst().sources().size());
  }

  private EvidenceCandidate candidate(
      UUID chunkId,
      UUID documentId,
      UUID versionId,
      int chunkIndex,
      String content,
      String questionId,
      double score) {
    UUID knowledgeBaseId = id(999);
    EvidenceSource source =
        new EvidenceSource(
            chunkId,
            knowledgeBaseId,
            "知识库",
            documentId,
            versionId,
            "文档.md",
            chunkIndex,
            "标题",
            chunkIndex + 1,
            chunkIndex + 2);
    RetrievalAttribution attribution =
        new RetrievalAttribution(
            chunkId, questionId, "embedding", .8, 1, RetrievalChannel.VECTOR, score);
    return new EvidenceCandidate(
        chunkId,
        chunkId,
        Set.of(questionId),
        knowledgeBaseId,
        "知识库",
        documentId,
        versionId,
        "文档.md",
        chunkIndex,
        content,
        "标题",
        chunkIndex + 1,
        chunkIndex + 2,
        List.of(source),
        List.of(attribution),
        score);
  }

  private UUID id(long value) {
    return new UUID(0, value);
  }
}
