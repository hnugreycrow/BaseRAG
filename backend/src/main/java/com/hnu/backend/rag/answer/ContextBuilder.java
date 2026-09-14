package com.hnu.backend.rag.answer;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.rag.retrieval.EvidenceCandidate;
import com.hnu.backend.rag.retrieval.RetrievalAttribution;
import com.hnu.backend.rag.retrieval.SearchHit;
import com.hnu.backend.rag.vo.SourceResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 将检索结果转换为模型可消费的证据文本和前端可展示的来源列表。 */
@Component
public class ContextBuilder {
  public record Context(String text, List<SourceResponse> sources) {}

  private final RagProperties config;
  private final JsonMapper json = JsonMapper.builder().build();

  public ContextBuilder(RagProperties config) {
    this.config = config;
  }

  /**
   * 按检索顺序去重分块并分配稳定的本轮引用编号。
   *
   * @param hits 检索候选结果
   * @return JSON Lines 格式的证据和对应来源
   */
  public Context build(List<SearchHit> hits) {
    StringBuilder text = new StringBuilder();
    List<SourceResponse> sources = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    for (SearchHit hit : hits) {
      if (!seen.add(hit.getChunkId())) continue;
      String id = "S" + (sources.size() + 1);
      SourceResponse source =
          new SourceResponse(
              id,
              hit.getKnowledgeBaseId(),
              hit.getKnowledgeBaseName(),
              hit.getChunkId(),
              hit.getDocumentId(),
              hit.getVersionId(),
              hit.getDocumentName(),
              hit.getHeading(),
              hit.getLineStart(),
              hit.getLineEnd(),
              hit.getSimilarity(),
              hit.getContent());
      String block = json.writeValueAsString(source) + "\n";
      text.append(block);
      sources.add(source);
    }
    return new Context(text.toString(), List.copyOf(sources));
  }

  /** 将阶段四融合后的不可变候选转换为现有来源协议，保持 citationId 和前端字段兼容。 */
  public Context buildEvidence(List<EvidenceCandidate> candidates) {
    StringBuilder text = new StringBuilder();
    List<SourceResponse> sources = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    for (EvidenceCandidate candidate : candidates) {
      if (!seen.add(candidate.chunkId())) continue;
      String id = "S" + (sources.size() + 1);
      // 对外兼容字段仍返回最佳原始相似度；候选排序已经只使用 RRF 融合分完成。
      double rawSimilarity =
          candidate.attributions().stream()
              .mapToDouble(RetrievalAttribution::rawSimilarity)
              .max()
              .orElse(0);
      SourceResponse source =
          new SourceResponse(
              id,
              candidate.knowledgeBaseId(),
              candidate.knowledgeBaseName(),
              candidate.chunkId(),
              candidate.documentId(),
              candidate.versionId(),
              candidate.documentName(),
              candidate.heading(),
              candidate.lineStart(),
              candidate.lineEnd(),
              rawSimilarity,
              candidate.content());
      text.append(json.writeValueAsString(source)).append('\n');
      sources.add(source);
    }
    return new Context(text.toString(), List.copyOf(sources));
  }
}
