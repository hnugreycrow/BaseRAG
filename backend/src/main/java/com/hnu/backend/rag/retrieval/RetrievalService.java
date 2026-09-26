package com.hnu.backend.rag.retrieval;

import com.hnu.backend.knowledgebase.entity.KnowledgeBase;
import com.hnu.backend.knowledgebase.mapper.KnowledgeBaseMapper;
import com.hnu.backend.model.client.EmbeddingClient;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.TraceReasonCatalog;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.observability.trace.TraceContext;
import com.hnu.backend.rag.configuration.RagProperties;
import com.hnu.backend.rag.execution.CancellationToken;
import com.hnu.backend.rag.execution.RagBudgetSnapshot;
import com.hnu.backend.rag.execution.StageBudget;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 负责跨向量模型检索知识库分块，并按模型内名次统一归并、排序结果。 */
@Service
public class RetrievalService {
  private static final long CANCELLATION_POLL_MS = 50;
  private final EmbeddingClient embedding;
  private final RetrievalMapper retrievalMapper;
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final RagProperties config;
  private final CandidateMerge candidateMerge;
  private final ExecutorService searchExecutor = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * 创建多模型向量检索服务。
   *
   * @param embedding 查询向量客户端
   * @param retrievalMapper 用户隔离的检索映射器
   * @param config RAG 检索配置
   * @param candidateMerge 跨模型候选合并器
   */
  @Autowired
  public RetrievalService(
      EmbeddingClient embedding,
      RetrievalMapper retrievalMapper,
      KnowledgeBaseMapper knowledgeBaseMapper,
      RagProperties config,
      CandidateMerge candidateMerge) {
    this.embedding = embedding;
    this.retrievalMapper = retrievalMapper;
    this.knowledgeBaseMapper = knowledgeBaseMapper;
    this.config = config;
    this.candidateMerge = candidateMerge;
  }

  /** 保留不启动 Spring 容器的检索单元测试构造方式。 */
  public RetrievalService(
      EmbeddingClient embedding,
      RetrievalMapper retrievalMapper,
      RagProperties config,
      CandidateMerge candidateMerge) {
    this(embedding, retrievalMapper, null, config, candidateMerge);
  }

  @PreDestroy
  void close() {
    searchExecutor.shutdownNow();
  }

  /**
   * 使用各知识库当前绑定的模型分别生成查询向量，再按模型内名次融合结果。
   *
   * @param ownerId 提问者标识；知识库范围由 SQL 中的管理员创建者条件限定
   * @param question 已规范化的检索问题
   * @return 按 RRF 融合分排列且不超过最终 Top K 的候选分块
   */
  public List<SearchHit> retrieve(UUID ownerId, String question) {
    return retrieve(ownerId, question, null);
  }

  /**
   * 在指定知识库集合中检索；未指定集合时保持全库检索行为。
   *
   * @param ownerId 所属用户标识
   * @param question 已规范化的检索问题
   * @param knowledgeBaseIds 允许检索的知识库；null 表示全部知识库
   * @return 按 RRF 融合分排列且不超过最终 Top K 的候选分块
   */
  public List<SearchHit> retrieve(UUID ownerId, String question, List<UUID> knowledgeBaseIds) {
    RagBudgetSnapshot snapshot = RagBudgetSnapshot.from(config);
    return retrieveCandidates(
            ownerId,
            "Q1",
            question,
            knowledgeBaseIds,
            snapshot.forSubQuestion("Q1"),
            CancellationToken.NONE)
        .stream()
        .limit(snapshot.defaultTopK())
        .map(this::toSearchHit)
        .toList();
  }

  /**
   * 为一个子问题执行完整向量通道召回，并保留各 Embedding 模型内的名次归因。
   *
   * <p>每个模型最多查询 recallBudget 条，随后先按 RRF 合并并再次截断到整个向量通道的 recallBudget， 因此模型数量增加不会线性放大该子问题进入全局融合的候选数。
   *
   * @param ownerId 提问者标识；外部知识库 ID 仍会与公共范围取交集
   * @param subQuestionId 子问题标识
   * @param question 子问题正文
   * @param knowledgeBaseIds 可选知识库范围；{@code null} 表示全部公共知识库
   * @param budget 当前子问题的检索预算
   * @param cancellationToken 异步取消信号
   * @return 可供全局合并的证据候选
   */
  public List<EvidenceCandidate> retrieveCandidates(
      UUID ownerId,
      String subQuestionId,
      String question,
      List<UUID> knowledgeBaseIds,
      StageBudget budget,
      CancellationToken cancellationToken) {
    return retrieveCandidates(
        ownerId,
        subQuestionId,
        question,
        knowledgeBaseIds,
        budget,
        cancellationToken,
        RagRunTrace.noop());
  }

  /**
   * 为一个子问题召回候选，并分别记录每个模型绑定的向量化与数据库查询。
   *
   * @param ownerId 所属用户标识
   * @param subQuestionId 子问题标识
   * @param question 子问题正文，仅用于模型调用，不进入 Trace
   * @param knowledgeBaseIds 可选知识库范围
   * @param budget 当前检索预算
   * @param cancellationToken 取消信号
   * @param trace 当前问答 Trace
   * @return 可供全局合并的证据候选
   */
  public List<EvidenceCandidate> retrieveCandidates(
      UUID ownerId,
      String subQuestionId,
      String question,
      List<UUID> knowledgeBaseIds,
      StageBudget budget,
      CancellationToken cancellationToken,
      TraceContext trace) {
    List<UUID> scope =
        knowledgeBaseIds == null ? null : knowledgeBaseIds.stream().distinct().toList();
    if (scope != null && scope.isEmpty()) {
      skipVectorStages(trace, subQuestionId, TraceReasonCatalog.EMPTY_KNOWLEDGE_SCOPE.code());
      return List.of();
    }
    if (!budget.vectorEnabled()) {
      skipVectorStages(trace, subQuestionId, TraceReasonCatalog.VECTOR_DISABLED.code());
      return List.of();
    }
    List<EvidenceCandidate> candidates = new ArrayList<>();
    var bindings =
        scope == null
            ? retrievalMapper.activeModelBindings(ownerId)
            : retrievalMapper.activeModelBindingsIn(ownerId, scope);
    if (bindings.isEmpty()) {
      skipVectorStages(trace, subQuestionId, TraceReasonCatalog.NO_EMBEDDING_BINDINGS.code());
      return List.of();
    }
    long remainingSearchNanos = TimeUnit.MILLISECONDS.toNanos(budget.timeoutMs());
    for (var binding : bindings) {
      cancellationToken.throwIfCancelled();
      if (remainingSearchNanos <= 0) {
        throw channelTimeout();
      }
      float[] vector =
          trace.execute(
              RagStageName.EMBEDDING,
              subQuestionId,
              1,
              embeddingSpan -> {
                embeddingSpan.model(binding.model(), binding.provider(), binding.model());
                try {
                  float[] result =
                      embedding
                          .embed(
                              binding.modelId(),
                              binding.provider(),
                              binding.model(),
                              binding.dimensions(),
                              List.of(question))
                          .getFirst();
                  embeddingSpan.success(1);
                  return result;
                } catch (RuntimeException error) {
                  if (ErrorCode.GENERATION_CANCELLED.code().equals(errorCode(error, ""))) {
                    embeddingSpan.error(error);
                  } else {
                    embeddingSpan.failed(errorCode(error, ErrorCode.EMBEDDING_FAILED.code()));
                  }
                  throw error;
                }
              });
      long searchStartedAt = System.nanoTime();
      long availableSearchNanos = remainingSearchNanos;
      List<SearchHit> hits =
          trace.execute(
              RagStageName.DATABASE_RETRIEVAL,
              subQuestionId,
              1,
              retrievalSpan -> {
                try {
                  List<SearchHit> result =
                      search(
                          ownerId,
                          scope,
                          vector,
                          binding,
                          budget.recallBudget(),
                          availableSearchNanos,
                          cancellationToken);
                  retrievalSpan.success(result.size());
                  return result;
                } catch (RuntimeException error) {
                  if (ErrorCode.GENERATION_CANCELLED.code().equals(errorCode(error, ""))) {
                    retrievalSpan.error(error);
                  } else {
                    retrievalSpan.failed(
                        errorCode(error, ErrorCode.DATABASE_RETRIEVAL_FAILED.code()));
                  }
                  throw error;
                }
              });
      remainingSearchNanos -= System.nanoTime() - searchStartedAt;
      cancellationToken.throwIfCancelled();
      List<SearchHit> ranked =
          hits.stream()
              .sorted(
                  Comparator.comparingDouble(SearchHit::getSimilarity)
                      .reversed()
                      .thenComparing(hit -> hit.getChunkId().toString()))
              .toList();
      for (int index = 0; index < ranked.size(); index++) {
        int rank = index + 1;
        SearchHit hit = ranked.get(index);
        double contribution = budget.vectorWeight() / (budget.rrfK() + rank);
        RetrievalAttribution attribution =
            new RetrievalAttribution(
                hit.getChunkId(),
                subQuestionId,
                binding.model(),
                hit.getSimilarity(),
                rank,
                RetrievalChannel.VECTOR,
                contribution);
        candidates.add(toCandidate(hit, subQuestionId, attribution));
      }
    }
    // 不同 Embedding 模型的原始相似度不可直接比较，只使用各自模型内名次产生的 RRF 分数合并。
    return candidateMerge.mergeAndSelect(candidates, List.of(subQuestionId), budget.recallBudget());
  }

  /** 绑定库与其余公共库分配 75%/25% 召回预算，每个模型只生成一次查询向量。 */
  public List<EvidenceCandidate> retrieveDirectedCandidates(
      UUID ownerId,
      String subQuestionId,
      String question,
      List<UUID> primaryKnowledgeBaseIds,
      StageBudget budget,
      CancellationToken cancellationToken,
      TraceContext trace) {
    if (primaryKnowledgeBaseIds == null || primaryKnowledgeBaseIds.isEmpty()) {
      return retrieveCandidates(
          ownerId, subQuestionId, question, null, budget, cancellationToken, trace);
    }
    if (!budget.vectorEnabled()) {
      skipVectorStages(trace, subQuestionId, TraceReasonCatalog.VECTOR_DISABLED.code());
      return List.of();
    }
    List<UUID> primary = primaryKnowledgeBaseIds.stream().distinct().toList();
    List<UUID> supplementalScope =
        knowledgeBaseMapper.selectWithDocumentCount(null, Integer.MAX_VALUE, 0).stream()
            .map(KnowledgeBase::getId)
            .filter(id -> !primary.contains(id))
            .toList();
    int supplementLimit =
        !supplementalScope.isEmpty() && budget.recallBudget() > 1
            ? Math.max(1, (int) Math.round(budget.recallBudget() * 0.25))
            : 0;
    int primaryLimit = budget.recallBudget() - supplementLimit;
    List<EvidenceCandidate> candidates = new ArrayList<>();
    var modelBindings = retrievalMapper.activeModelBindings(ownerId);
    if (modelBindings.isEmpty()) {
      skipVectorStages(trace, subQuestionId, TraceReasonCatalog.NO_EMBEDDING_BINDINGS.code());
      return List.of();
    }
    long remainingSearchNanos = TimeUnit.MILLISECONDS.toNanos(budget.timeoutMs());
    for (var binding : modelBindings) {
      cancellationToken.throwIfCancelled();
      float[] vector =
          trace.execute(
              RagStageName.EMBEDDING,
              subQuestionId,
              1,
              embeddingSpan -> {
                embeddingSpan.model(binding.model(), binding.provider(), binding.model());
                try {
                  float[] result =
                      embedding
                          .embed(
                              binding.modelId(),
                              binding.provider(),
                              binding.model(),
                              binding.dimensions(),
                              List.of(question))
                          .getFirst();
                  embeddingSpan.success(1);
                  return result;
                } catch (RuntimeException error) {
                  if (ErrorCode.GENERATION_CANCELLED.code().equals(errorCode(error, ""))) {
                    embeddingSpan.error(error);
                  } else {
                    embeddingSpan.failed(errorCode(error, ErrorCode.EMBEDDING_FAILED.code()));
                  }
                  throw error;
                }
              });
      long availableSearchNanos = remainingSearchNanos;
      remainingSearchNanos =
          trace.execute(
              RagStageName.DATABASE_RETRIEVAL,
              subQuestionId,
              2,
              retrievalSpan -> {
                long remaining = availableSearchNanos;
                try {
                  long started = System.nanoTime();
                  List<SearchHit> focused =
                      search(
                          ownerId,
                          primary,
                          vector,
                          binding,
                          primaryLimit,
                          remaining,
                          cancellationToken);
                  remaining -= System.nanoTime() - started;
                  addRanked(candidates, focused, subQuestionId, binding, budget);
                  if (supplementLimit > 0) {
                    started = System.nanoTime();
                    List<SearchHit> supplementary =
                        search(
                            ownerId,
                            supplementalScope,
                            vector,
                            binding,
                            supplementLimit,
                            remaining,
                            cancellationToken);
                    remaining -= System.nanoTime() - started;
                    addRanked(candidates, supplementary, subQuestionId, binding, budget);
                  }
                  retrievalSpan.success(candidates.size());
                  return remaining;
                } catch (RuntimeException error) {
                  if (ErrorCode.GENERATION_CANCELLED.code().equals(errorCode(error, ""))) {
                    retrievalSpan.error(error);
                  } else {
                    retrievalSpan.failed(
                        errorCode(error, ErrorCode.DATABASE_RETRIEVAL_FAILED.code()));
                  }
                  throw error;
                }
              });
    }
    return candidateMerge.mergeAndSelect(candidates, List.of(subQuestionId), budget.recallBudget());
  }

  private void addRanked(
      List<EvidenceCandidate> candidates,
      List<SearchHit> hits,
      String subQuestionId,
      EmbeddingBinding binding,
      StageBudget budget) {
    List<SearchHit> ranked =
        hits.stream()
            .sorted(
                Comparator.comparingDouble(SearchHit::getSimilarity)
                    .reversed()
                    .thenComparing(hit -> hit.getChunkId().toString()))
            .toList();
    for (int index = 0; index < ranked.size(); index++) {
      SearchHit hit = ranked.get(index);
      int rank = index + 1;
      RetrievalAttribution attribution =
          new RetrievalAttribution(
              hit.getChunkId(),
              subQuestionId,
              binding.model(),
              hit.getSimilarity(),
              rank,
              RetrievalChannel.VECTOR,
              budget.vectorWeight() / (budget.rrfK() + rank));
      candidates.add(toCandidate(hit, subQuestionId, attribution));
    }
  }

  /** 通道预算只统计数据库召回，不统计生成查询向量的模型耗时。 */
  private List<SearchHit> search(
      UUID ownerId,
      List<UUID> scope,
      float[] vector,
      EmbeddingBinding binding,
      int recallBudget,
      long remainingNanos,
      CancellationToken cancellationToken) {
    if (remainingNanos <= 0) {
      throw channelTimeout();
    }
    String literal = EmbeddingClient.literal(vector);
    Future<List<SearchHit>> future =
        searchExecutor.submit(
            () ->
                scope == null
                    ? retrievalMapper.searchAll(
                        ownerId,
                        literal,
                        binding.modelId(),
                        binding.provider(),
                        binding.model(),
                        binding.dimensions(),
                        recallBudget)
                    : retrievalMapper.searchIn(
                        ownerId,
                        scope,
                        literal,
                        binding.modelId(),
                        binding.provider(),
                        binding.model(),
                        binding.dimensions(),
                        recallBudget));
    long deadline = System.nanoTime() + remainingNanos;
    try {
      while (true) {
        cancellationToken.throwIfCancelled();
        long waitNanos = deadline - System.nanoTime();
        if (waitNanos <= 0) {
          throw channelTimeout();
        }
        try {
          return future.get(
              Math.min(waitNanos, TimeUnit.MILLISECONDS.toNanos(CANCELLATION_POLL_MS)),
              TimeUnit.NANOSECONDS);
        } catch (TimeoutException ignored) {
          // 短轮询使请求取消和检索预算都能及时中断数据库查询。
        }
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw ApiException.cancelled();
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(cause);
    } finally {
      if (!future.isDone()) {
        future.cancel(true);
      }
    }
  }

  private ApiException channelTimeout() {
    return ApiException.upstream(ErrorCode.SUBQUESTION_TIMEOUT, "向量检索通道超时");
  }

  /** 记录向量通道未执行时的两个非降级阶段。 */
  private void skipVectorStages(TraceContext trace, String subQuestionId, String reasonCode) {
    trace.skipped(RagStageName.EMBEDDING, subQuestionId, reasonCode);
    trace.skipped(RagStageName.DATABASE_RETRIEVAL, subQuestionId, reasonCode);
  }

  /** 将异常转换为不包含消息正文的稳定错误码。 */
  private String errorCode(RuntimeException error, String fallback) {
    return error instanceof com.hnu.backend.shared.error.ApiException api ? api.code() : fallback;
  }

  private EvidenceCandidate toCandidate(
      SearchHit hit, String subQuestionId, RetrievalAttribution attribution) {
    return new EvidenceCandidate(
        hit.getChunkId(),
        hit.getChunkId(),
        java.util.Set.of(subQuestionId),
        hit.getKnowledgeBaseId(),
        hit.getKnowledgeBaseName(),
        hit.getDocumentId(),
        hit.getVersionId(),
        hit.getDocumentName(),
        hit.getChunkIndex(),
        hit.getContent(),
        hit.getHeading(),
        hit.getLineStart(),
        hit.getLineEnd(),
        List.of(
            new EvidenceSource(
                hit.getChunkId(),
                hit.getKnowledgeBaseId(),
                hit.getKnowledgeBaseName(),
                hit.getDocumentId(),
                hit.getVersionId(),
                hit.getDocumentName(),
                hit.getChunkIndex(),
                hit.getHeading(),
                hit.getLineStart(),
                hit.getLineEnd())),
        List.of(attribution),
        attribution.fusionContribution(),
        // 历史检索结果缺少新字段时按 Markdown 行号兼容。
        hit.getFormat() == null ? "MARKDOWN" : hit.getFormat(),
        hit.getSourceUnit() == null ? "LINE" : hit.getSourceUnit());
  }

  private SearchHit toSearchHit(EvidenceCandidate candidate) {
    SearchHit hit = new SearchHit();
    hit.setKnowledgeBaseId(candidate.knowledgeBaseId());
    hit.setKnowledgeBaseName(candidate.knowledgeBaseName());
    hit.setChunkId(candidate.chunkId());
    hit.setDocumentId(candidate.documentId());
    hit.setVersionId(candidate.versionId());
    hit.setDocumentName(candidate.documentName());
    hit.setChunkIndex(candidate.chunkIndex());
    hit.setContent(candidate.content());
    hit.setHeading(candidate.heading());
    hit.setLineStart(candidate.lineStart());
    hit.setLineEnd(candidate.lineEnd());
    hit.setFormat(candidate.format());
    hit.setSourceUnit(candidate.sourceUnit());
    hit.setSimilarity(
        candidate.attributions().stream()
            .mapToDouble(RetrievalAttribution::rawSimilarity)
            .max()
            .orElse(0));
    return hit;
  }

  /** 兼容根 Trace 入口；内部显式传递父节点上下文。 */
  public List<EvidenceCandidate> retrieveCandidates(
      UUID ownerId,
      String subQuestionId,
      String question,
      List<UUID> knowledgeBaseIds,
      StageBudget budget,
      CancellationToken cancellationToken,
      RagRunTrace trace) {
    return retrieveCandidates(
        ownerId,
        subQuestionId,
        question,
        knowledgeBaseIds,
        budget,
        cancellationToken,
        trace.context());
  }

  /** 兼容根 Trace 入口；内部显式传递父节点上下文。 */
  public List<EvidenceCandidate> retrieveDirectedCandidates(
      UUID ownerId,
      String subQuestionId,
      String question,
      List<UUID> primaryKnowledgeBaseIds,
      StageBudget budget,
      CancellationToken cancellationToken,
      RagRunTrace trace) {
    return retrieveDirectedCandidates(
        ownerId,
        subQuestionId,
        question,
        primaryKnowledgeBaseIds,
        budget,
        cancellationToken,
        trace.context());
  }
}
