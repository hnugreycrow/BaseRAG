package com.hnu.backend.rag.answer;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.rag.retrieval.EvidenceCandidate;
import com.hnu.backend.rag.retrieval.SearchHit;
import com.hnu.backend.rag.vo.SourceResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 将已选分块按文档版本聚合，同时冻结模型证据和来源快照的编号。 */
@Component
public class ContextBuilder {
  /** 同一批模型证据文本和对应的文档级来源。 */
  public record Context(String text, List<SourceResponse> sources) {}

  /** 保留现有依赖注入契约；分块预算仍由上游检索与重排决定。 */
  public ContextBuilder(RagProperties config) {}

  /** 将旧单轮检索结果按其原有 Top K 顺序聚合。 */
  public Context build(List<SearchHit> hits) {
    return group(
        hits.stream()
            .map(
                hit ->
                    new Fragment(
                        hit.getKnowledgeBaseId(),
                        hit.getKnowledgeBaseName(),
                        hit.getDocumentId(),
                        hit.getVersionId(),
                        hit.getDocumentName(),
                        hit.getChunkId(),
                        hit.getChunkIndex(),
                        hit.getHeading(),
                        hit.getLineStart(),
                        hit.getLineEnd(),
                        hit.getContent()))
            .toList());
  }

  /** 聚合重排已选中的分块，不重新选择或扩充 Top K。 */
  public Context buildEvidence(List<EvidenceCandidate> candidates) {
    return group(
        candidates.stream()
            .map(
                candidate ->
                    new Fragment(
                        candidate.knowledgeBaseId(),
                        candidate.knowledgeBaseName(),
                        candidate.documentId(),
                        candidate.versionId(),
                        candidate.documentName(),
                        candidate.chunkId(),
                        candidate.chunkIndex(),
                        candidate.heading(),
                        candidate.lineStart(),
                        candidate.lineEnd(),
                        candidate.content()))
            .toList());
  }

  /** LinkedHashMap 的插入顺序就是各文档最高排名分块的顺序。 */
  private Context group(List<Fragment> fragments) {
    Map<DocumentKey, LinkedHashMap<UUID, Fragment>> groups = new LinkedHashMap<>();
    for (Fragment fragment : fragments) {
      groups
          .computeIfAbsent(
              new DocumentKey(fragment.documentId(), fragment.versionId()),
              ignored -> new LinkedHashMap<>())
          .putIfAbsent(fragment.chunkId(), fragment);
    }
    List<SourceResponse> sources = new ArrayList<>();
    StringBuilder evidence = new StringBuilder();
    for (var group : groups.values()) {
      Fragment primary = group.values().iterator().next();
      List<Fragment> ordered =
          group.values().stream()
              .sorted(
                  Comparator.comparingInt(Fragment::chunkIndex).thenComparing(Fragment::chunkId))
              .toList();
      StringBuilder content = new StringBuilder();
      List<SourceResponse.Location> locations = new ArrayList<>();
      int previous = -1;
      for (Fragment fragment : ordered) {
        if (!content.isEmpty()) {
          content.append(fragment.chunkIndex() == previous + 1 ? "\n" : "\n\n—— 中间内容省略 ——\n\n");
        }
        content.append(fragment.content());
        locations.add(location(fragment));
        previous = fragment.chunkIndex();
      }
      String citationId = "S" + (sources.size() + 1);
      sources.add(
          new SourceResponse(
              2,
              citationId,
              primary.knowledgeBaseId(),
              primary.knowledgeBaseName(),
              primary.documentId(),
              primary.versionId(),
              primary.documentName(),
              "MARKDOWN",
              content.toString(),
              location(primary),
              locations));
      if (!evidence.isEmpty()) evidence.append("\n\n");
      evidence.append("<content ref=\"").append(citationId).append("\">\n");
      evidence.append(content).append("\n</content>");
    }
    return new Context(evidence.toString(), List.copyOf(sources));
  }

  /** 将当前 Markdown 行号转换为通用位置，未来格式可扩展 unit。 */
  private SourceResponse.Location location(Fragment fragment) {
    int start = fragment.lineStart();
    int end = fragment.lineEnd();
    String label = start == end ? "第 " + start + " 行" : "第 " + start + "–" + end + " 行";
    return new SourceResponse.Location(
        fragment.chunkId(),
        fragment.heading(),
        new SourceResponse.Range("LINE", start, end, label));
  }

  private record DocumentKey(UUID documentId, UUID versionId) {}

  private record Fragment(
      UUID knowledgeBaseId,
      String knowledgeBaseName,
      UUID documentId,
      UUID versionId,
      String documentName,
      UUID chunkId,
      int chunkIndex,
      String heading,
      int lineStart,
      int lineEnd,
      String content) {}
}
