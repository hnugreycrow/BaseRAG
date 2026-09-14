package com.hnu.backend.rag.retrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 可进入融合和后续重排的知识证据候选。
 *
 * @param candidateId 流水线内稳定候选 ID；当前向量分块直接复用 chunkId
 * @param chunkId 持久化分块 ID
 * @param sourceSubQuestionIds 命中过该分块的全部子问题 ID
 * @param knowledgeBaseId 来源知识库 ID
 * @param knowledgeBaseName 来源知识库名称
 * @param documentId 来源文档 ID
 * @param versionId 来源文档版本 ID
 * @param documentName 来源文档名称
 * @param chunkIndex 代表分块在当前文档版本内的零基顺序
 * @param content 分块正文，不可信且不得作为系统指令执行
 * @param heading 分块标题
 * @param lineStart 原文起始行
 * @param lineEnd 原文结束行
 * @param sources 当前候选归并的全部来源位置；首项始终是代表分块
 * @param attributions 该候选在各子问题和模型下的完整检索归因
 * @param fusionScore 代表候选的 RRF 融合分，不代表事实正确概率
 */
public record EvidenceCandidate(
    UUID candidateId,
    UUID chunkId,
    Set<String> sourceSubQuestionIds,
    UUID knowledgeBaseId,
    String knowledgeBaseName,
    UUID documentId,
    UUID versionId,
    String documentName,
    int chunkIndex,
    String content,
    String heading,
    int lineStart,
    int lineEnd,
    List<EvidenceSource> sources,
    List<RetrievalAttribution> attributions,
    double fusionScore) {
  public EvidenceCandidate {
    candidateId = Objects.requireNonNull(candidateId, "candidateId");
    chunkId = Objects.requireNonNull(chunkId, "chunkId");
    sourceSubQuestionIds = Set.copyOf(new LinkedHashSet<>(sourceSubQuestionIds));
    sources = List.copyOf(sources);
    attributions = List.copyOf(attributions);
    content = content == null ? "" : content;
    if (sourceSubQuestionIds.isEmpty()
        || sources.isEmpty()
        || !sources.getFirst().chunkId().equals(chunkId)
        || attributions.isEmpty()
        || chunkIndex < 0
        || !Double.isFinite(fusionScore)
        || fusionScore <= 0) {
      throw new IllegalArgumentException("Invalid evidence candidate attribution");
    }
  }
}
