package com.hnu.backend.rag.deduplication;

import com.hnu.backend.rag.execution.RagBudgetSnapshot;
import com.hnu.backend.rag.retrieval.EvidenceCandidate;
import com.hnu.backend.rag.retrieval.EvidenceSource;
import com.hnu.backend.rag.retrieval.RetrievalAttribution;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 按分块 ID、规范化正文和相邻正文重叠率依次归并重复证据。 */
@Component
public class DeduplicationStage {
  private static final Logger log = LoggerFactory.getLogger(DeduplicationStage.class);
  private static final Comparator<EvidenceCandidate> CANDIDATE_ORDER =
      Comparator.comparingDouble(EvidenceCandidate::fusionScore)
          .reversed()
          .thenComparing(candidate -> candidate.candidateId().toString());

  public DeduplicationResult execute(List<EvidenceCandidate> input, RagBudgetSnapshot budget) {
    long startedAt = System.nanoTime();
    List<Group> groups =
        input.stream()
            .sorted(CANDIDATE_ORDER)
            .map(Group::single)
            .collect(ArrayList::new, List::add, List::addAll);

    int before = groups.size();
    groups = mergeByKey(groups, group -> group.representative().chunkId().toString(), false);
    int exactMerged = before - groups.size();

    before = groups.size();
    groups = mergeByKey(groups, this::normalizedHash, true);
    int normalizedMerged = before - groups.size();

    before = groups.size();
    groups = mergeAdjacentOverlaps(groups, budget.deduplicationOverlapThreshold());
    int overlapMerged = before - groups.size();

    List<EvidenceCandidate> candidates =
        groups.stream().map(Group::build).sorted(CANDIDATE_ORDER).toList();
    log.info(
        "deduplication completed input={} output={} exactMerged={} normalizedMerged={} overlapMerged={} deduplicationMs={}",
        input.size(),
        candidates.size(),
        exactMerged,
        normalizedMerged,
        overlapMerged,
        elapsedMillis(startedAt));
    return new DeduplicationResult(candidates, exactMerged, normalizedMerged, overlapMerged);
  }

  private List<Group> mergeByKey(
      List<Group> groups, Function<Group, String> keyFunction, boolean keepBlankSeparate) {
    Map<String, Group> merged = new LinkedHashMap<>();
    int blankIndex = 0;
    for (Group group : groups) {
      String key = keyFunction.apply(group);
      // 空正文不能共享同一个哈希分组，否则所有空分块都会被误判为相同证据。
      if (keepBlankSeparate && key.isEmpty()) {
        key = "#blank-" + blankIndex++;
      }
      merged.merge(key, group, Group::merge);
    }
    return new ArrayList<>(merged.values());
  }

  private List<Group> mergeAdjacentOverlaps(List<Group> groups, double threshold) {
    UnionFind union = new UnionFind(groups.size());
    for (int left = 0; left < groups.size(); left++) {
      for (int right = left + 1; right < groups.size(); right++) {
        if (overlaps(groups.get(left), groups.get(right), threshold)) {
          union.join(left, right);
        }
      }
    }
    Map<Integer, Group> merged = new LinkedHashMap<>();
    for (int index = 0; index < groups.size(); index++) {
      merged.merge(union.root(index), groups.get(index), Group::merge);
    }
    return new ArrayList<>(merged.values());
  }

  private boolean overlaps(Group left, Group right, double threshold) {
    for (EvidenceCandidate first : left.members) {
      for (EvidenceCandidate second : right.members) {
        if (!hasAdjacentSource(first.sources(), second.sources())) {
          continue;
        }
        String firstText = normalize(first.content());
        String secondText = normalize(second.content());
        if (!firstText.isEmpty()
            && !secondText.isEmpty()
            && trigramOverlap(firstText, secondText) >= threshold) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean hasAdjacentSource(List<EvidenceSource> left, List<EvidenceSource> right) {
    for (EvidenceSource first : left) {
      for (EvidenceSource second : right) {
        if (first.documentId().equals(second.documentId())
            && first.versionId().equals(second.versionId())
            && Math.abs(first.chunkIndex() - second.chunkIndex()) == 1) {
          return true;
        }
      }
    }
    return false;
  }

  private double trigramOverlap(String left, String right) {
    Set<String> leftTrigrams = trigrams(left);
    Set<String> rightTrigrams = trigrams(right);
    if (leftTrigrams.isEmpty() || rightTrigrams.isEmpty()) {
      return 0;
    }
    Set<String> smaller =
        leftTrigrams.size() <= rightTrigrams.size() ? leftTrigrams : rightTrigrams;
    Set<String> larger = smaller == leftTrigrams ? rightTrigrams : leftTrigrams;
    long intersection = smaller.stream().filter(larger::contains).count();
    // 使用较短文本的三元组数作为分母，可识别被相邻分块大面积包含的重叠正文。
    return (double) intersection / smaller.size();
  }

  private Set<String> trigrams(String value) {
    int[] codePoints = value.codePoints().toArray();
    if (codePoints.length < 3) {
      return Set.of();
    }
    Set<String> result = new HashSet<>();
    for (int index = 0; index <= codePoints.length - 3; index++) {
      result.add(new String(codePoints, index, 3));
    }
    return result;
  }

  private String normalizedHash(Group group) {
    String normalized = normalize(group.representative().content());
    if (normalized.isEmpty()) {
      return "";
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-256", impossible);
    }
  }

  private String normalize(String value) {
    return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
        .strip()
        .replaceAll("\\s+", " ")
        .toLowerCase(Locale.ROOT);
  }

  private long elapsedMillis(long startedAt) {
    return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
  }

  private static final class Group {
    private final List<EvidenceCandidate> members;

    private Group(List<EvidenceCandidate> members) {
      this.members = members;
    }

    private static Group single(EvidenceCandidate candidate) {
      return new Group(new ArrayList<>(List.of(candidate)));
    }

    private static Group merge(Group left, Group right) {
      List<EvidenceCandidate> combined = new ArrayList<>(left.members);
      combined.addAll(right.members);
      return new Group(combined);
    }

    private EvidenceCandidate representative() {
      return members.stream().min(CANDIDATE_ORDER).orElseThrow();
    }

    private EvidenceCandidate build() {
      EvidenceCandidate representative = representative();
      Set<String> questionIds = new LinkedHashSet<>();
      Set<EvidenceSource> sources = new LinkedHashSet<>();
      Set<RetrievalAttribution> attributions = new LinkedHashSet<>();
      members.stream()
          .sorted(CANDIDATE_ORDER)
          .forEach(
              candidate -> {
                questionIds.addAll(candidate.sourceSubQuestionIds());
                sources.addAll(candidate.sources());
                attributions.addAll(candidate.attributions());
              });
      // 代表来源必须位于首项，便于对外兼容字段与候选正文保持同一个出处。
      List<EvidenceSource> orderedSources = new ArrayList<>();
      representative.sources().stream()
          .filter(source -> source.chunkId().equals(representative.chunkId()))
          .findFirst()
          .ifPresent(orderedSources::add);
      sources.stream()
          .filter(source -> !orderedSources.contains(source))
          .forEach(orderedSources::add);
      return new EvidenceCandidate(
          representative.candidateId(),
          representative.chunkId(),
          questionIds,
          representative.knowledgeBaseId(),
          representative.knowledgeBaseName(),
          representative.documentId(),
          representative.versionId(),
          representative.documentName(),
          representative.chunkIndex(),
          representative.content(),
          representative.heading(),
          representative.lineStart(),
          representative.lineEnd(),
          orderedSources,
          new ArrayList<>(attributions),
          representative.fusionScore(),
          // 归并后仍使用代表分块的文件格式和来源单位。
          representative.format(),
          representative.sourceUnit());
    }
  }

  /** 并查集保证 A≈B、B≈C 时三个候选归入同一组，不受遍历顺序影响。 */
  private static final class UnionFind {
    private final int[] parent;

    private UnionFind(int size) {
      parent = new int[size];
      for (int index = 0; index < size; index++) {
        parent[index] = index;
      }
    }

    private int root(int value) {
      if (parent[value] != value) {
        parent[value] = root(parent[value]);
      }
      return parent[value];
    }

    private void join(int left, int right) {
      int leftRoot = root(left);
      int rightRoot = root(right);
      if (leftRoot != rightRoot) {
        parent[rightRoot] = leftRoot;
      }
    }
  }
}
