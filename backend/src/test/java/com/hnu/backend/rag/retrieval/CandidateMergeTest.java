package com.hnu.backend.rag.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证跨子问题候选归并时的来源、归因和稳定排序。 */
public class CandidateMergeTest {
  private final CandidateMerge merge = new CandidateMerge();

  @Test
  void mergesDuplicateChunkAndKeepsEveryAttribution() {
    UUID chunkId = id(1);
    EvidenceCandidate first = candidate(chunkId, "Q1", "model-a", .91, 1, .05);
    EvidenceCandidate second = candidate(chunkId, "Q2", "model-b", .72, 2, .04);

    List<EvidenceCandidate> result =
        merge.mergeAndSelect(List.of(first, second), List.of("Q1", "Q2"), 40);

    assertEquals(1, result.size());
    assertEquals(Set.of("Q1", "Q2"), result.getFirst().sourceSubQuestionIds());
    assertEquals(2, result.getFirst().attributions().size());
    assertEquals(.09, result.getFirst().fusionScore(), 0.000001);
  }

  @Test
  void reservesCoverageBeforeFillingByFusionScore() {
    EvidenceCandidate q1High = candidate(id(1), "Q1", "model-a", .9, 1, .10);
    EvidenceCandidate q1Second = candidate(id(2), "Q1", "model-a", .8, 2, .09);
    EvidenceCandidate q2Low = candidate(id(3), "Q2", "model-a", .3, 3, .01);

    List<EvidenceCandidate> result =
        merge.mergeAndSelect(List.of(q1High, q1Second, q2Low), List.of("Q1", "Q2"), 2);

    assertEquals(List.of(id(1), id(3)), result.stream().map(EvidenceCandidate::chunkId).toList());
    assertTrue(
        result.stream().anyMatch(candidate -> candidate.sourceSubQuestionIds().contains("Q2")));
  }

  @Test
  void usesCandidateIdAsStableTieBreaker() {
    EvidenceCandidate later = candidate(id(2), "Q1", "model-a", .9, 1, .05);
    EvidenceCandidate earlier = candidate(id(1), "Q1", "model-a", .8, 2, .05);

    List<EvidenceCandidate> result =
        merge.mergeAndSelect(List.of(later, earlier), List.of("Q1"), 10);

    assertEquals(
        List.of(id(1), id(2)), result.stream().map(EvidenceCandidate::candidateId).toList());
  }

  /**
   * 创建拥有单条向量检索归因的候选，供归并测试复用。
   *
   * @param id 同时作为候选、分块和来源标识
   * @param subQuestionId 命中该候选的子问题
   * @param model 生成查询向量的模型
   * @param rawSimilarity 原始相似度
   * @param rank 模型内一基名次
   * @param contribution 本次检索的融合分贡献
   * @return 可用于候选归并的测试数据
   */
  public static EvidenceCandidate candidate(
      UUID id,
      String subQuestionId,
      String model,
      double rawSimilarity,
      int rank,
      double contribution) {
    RetrievalAttribution attribution =
        new RetrievalAttribution(
            id, subQuestionId, model, rawSimilarity, rank, RetrievalChannel.VECTOR, contribution);
    UUID knowledgeBaseId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    EvidenceSource source =
        new EvidenceSource(
            id, knowledgeBaseId, "知识库", documentId, versionId, "文档.md", 0, "标题", 1, 2);
    return new EvidenceCandidate(
        id,
        id,
        Set.of(subQuestionId),
        knowledgeBaseId,
        "知识库",
        documentId,
        versionId,
        "文档.md",
        0,
        "正文",
        "标题",
        1,
        2,
        List.of(source),
        List.of(attribution),
        contribution);
  }

  /** 使用固定低位生成稳定 UUID，避免随机 ID 干扰顺序断言。 */
  public static UUID id(long value) {
    return new UUID(0, value);
  }
}
