package com.hnu.backend.rag.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 按检索归因合并重复分块，并在截断时保护各知识型子问题的最低覆盖。 */
@Component
public class CandidateMerge {
  private static final Comparator<EvidenceCandidate> ORDER =
      Comparator.comparingDouble(EvidenceCandidate::fusionScore)
          .reversed()
          .thenComparing(candidate -> candidate.candidateId().toString());

  public List<EvidenceCandidate> mergeAndSelect(
      List<EvidenceCandidate> input, List<String> orderedSubQuestionIds, int limit) {
    List<EvidenceCandidate> merged = mergeByChunk(input);
    if (limit <= 0 || merged.size() <= limit) return List.copyOf(merged);

    Map<UUID, EvidenceCandidate> selected = new LinkedHashMap<>();
    // 先按问题规划顺序预留一条证据，避免高频问题把较弱但必要的子问题完全挤出候选池。
    for (String subQuestionId : orderedSubQuestionIds) {
      merged.stream()
          .filter(candidate -> candidate.sourceSubQuestionIds().contains(subQuestionId))
          .findFirst()
          .ifPresent(candidate -> selected.putIfAbsent(candidate.candidateId(), candidate));
      if (selected.size() == limit) break;
    }
    for (EvidenceCandidate candidate : merged) {
      if (selected.size() == limit) break;
      selected.putIfAbsent(candidate.candidateId(), candidate);
    }
    return selected.values().stream().sorted(ORDER).toList();
  }

  private List<EvidenceCandidate> mergeByChunk(List<EvidenceCandidate> input) {
    Map<UUID, Accumulator> merged = new LinkedHashMap<>();
    for (EvidenceCandidate candidate : input) {
      merged
          .computeIfAbsent(candidate.chunkId(), ignored -> new Accumulator(candidate))
          .add(candidate);
    }
    return merged.values().stream().map(Accumulator::build).sorted(ORDER).toList();
  }

  private static final class Accumulator {
    private final EvidenceCandidate representative;
    private final Set<String> sourceSubQuestionIds = new LinkedHashSet<>();
    private final Set<RetrievalAttribution> attributions = new LinkedHashSet<>();

    private Accumulator(EvidenceCandidate representative) {
      this.representative = representative;
    }

    private void add(EvidenceCandidate candidate) {
      sourceSubQuestionIds.addAll(candidate.sourceSubQuestionIds());
      attributions.addAll(candidate.attributions());
    }

    private EvidenceCandidate build() {
      // 融合分由唯一归因重新求和，防止相同 Mapper 结果被重复加入时意外放大分数。
      double score =
          attributions.stream().mapToDouble(RetrievalAttribution::fusionContribution).sum();
      return new EvidenceCandidate(
          representative.candidateId(),
          representative.chunkId(),
          sourceSubQuestionIds,
          representative.knowledgeBaseId(),
          representative.knowledgeBaseName(),
          representative.documentId(),
          representative.versionId(),
          representative.documentName(),
          representative.content(),
          representative.heading(),
          representative.lineStart(),
          representative.lineEnd(),
          new ArrayList<>(attributions),
          score);
    }
  }
}
