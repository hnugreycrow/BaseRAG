package com.hnu.backend.rag.rerank;

import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.observability.trace.TraceContext;
import com.hnu.backend.rag.execution.CancellationToken;
import com.hnu.backend.rag.execution.ExecutionResult;
import com.hnu.backend.rag.execution.RagBudgetSnapshot;
import com.hnu.backend.rag.execution.SubQuestionExecution;
import com.hnu.backend.rag.planning.QueryPlan;
import com.hnu.backend.rag.retrieval.CandidateMerge;
import com.hnu.backend.rag.retrieval.EvidenceCandidate;
import com.hnu.backend.rag.routing.IntentType;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 使用专用模型重排去重候选，并在任何非取消失败时确定性降级。 */
@Component
public class RerankStage {
  private static final Logger log = LoggerFactory.getLogger(RerankStage.class);
  private static final long CANCELLATION_POLL_MS = 50;
  private static final Comparator<EvidenceCandidate> FALLBACK_ORDER =
      Comparator.comparingDouble(EvidenceCandidate::fusionScore)
          .reversed()
          .thenComparing(candidate -> candidate.candidateId().toString());

  private final CandidateReranker reranker;
  private final CandidateMerge candidateMerge;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * 创建证据候选重排阶段。
   *
   * @param reranker 候选重排模型端口
   * @param candidateMerge 候选截断和配额分配器
   */
  public RerankStage(CandidateReranker reranker, CandidateMerge candidateMerge) {
    this.reranker = reranker;
    this.candidateMerge = candidateMerge;
  }

  /**
   * 重排候选；兼容调用不采集 Trace。
   *
   * @param plan 查询计划
   * @param execution 子问题执行结果
   * @param deduplicatedCandidates 去重候选
   * @param cancellationToken 取消信号
   * @return 重排或确定性降级结果
   */
  public RerankResult execute(
      QueryPlan plan,
      ExecutionResult execution,
      List<EvidenceCandidate> deduplicatedCandidates,
      CancellationToken cancellationToken) {
    return execute(plan, execution, deduplicatedCandidates, cancellationToken, RagRunTrace.noop());
  }

  /**
   * 重排证据候选并记录模型信息、候选数量和确定性降级。
   *
   * @param plan 查询计划
   * @param execution 子问题执行结果
   * @param deduplicatedCandidates 去重候选
   * @param cancellationToken 取消信号
   * @param trace 当前问答 Trace
   * @return 重排结果
   */
  public RerankResult execute(
      QueryPlan plan,
      ExecutionResult execution,
      List<EvidenceCandidate> deduplicatedCandidates,
      CancellationToken cancellationToken,
      TraceContext trace) {
    long startedAt = System.nanoTime();
    cancellationToken.throwIfCancelled();
    RagBudgetSnapshot budget = execution.budget();
    List<String> knowledgeQuestionIds = knowledgeQuestionIds(execution);
    List<EvidenceCandidate> input =
        candidateMerge.mergeAndSelect(
            deduplicatedCandidates, knowledgeQuestionIds, budget.rerankInputLimit());
    return trace.execute(
        RagStageName.RERANK,
        null,
        input.size(),
        span -> {
          if (input.isEmpty()) {
            return observed(
                span,
                result(
                    List.of(),
                    List.of(),
                    RerankResult.Status.EMPTY,
                    "NO_RERANK_INPUT",
                    null,
                    startedAt));
          }
          if (!budget.rerankEnabled()) {
            return observed(
                span,
                fallback(
                    input,
                    knowledgeQuestionIds,
                    budget.selectedEvidenceLimit(),
                    RerankResult.Status.DISABLED,
                    "RERANK_DISABLED",
                    null,
                    startedAt));
          }

          CandidateReranker.Output output = null;
          try {
            output = await(plan.standaloneQuestion(), input, cancellationToken);
            cancellationToken.throwIfCancelled();
            if (output.noop()) {
              return observed(
                  span,
                  fallback(
                      input,
                      knowledgeQuestionIds,
                      budget.selectedEvidenceLimit(),
                      RerankResult.Status.DEGRADED,
                      "RERANK_NOOP",
                      output,
                      startedAt));
            }
            List<RerankDecision> ranking = modelRanking(input, output.scores());
            Set<UUID> selectedIds =
                selectIds(ranking, knowledgeQuestionIds, budget.selectedEvidenceLimit());
            List<RerankDecision> decisions = markSelected(ranking, selectedIds);
            List<EvidenceCandidate> selected =
                decisions.stream()
                    .filter(RerankDecision::selected)
                    .map(RerankDecision::candidate)
                    .toList();
            return observed(
                span,
                result(
                    selected,
                    decisions,
                    RerankResult.Status.SUCCESS,
                    "RERANK_COMPLETED",
                    output,
                    startedAt));
          } catch (ApiException error) {
            if (cancellationToken.cancelled()
                || ErrorCode.GENERATION_CANCELLED.code().equals(error.code())) throw error;
            return observed(
                span,
                fallback(
                    input,
                    knowledgeQuestionIds,
                    budget.selectedEvidenceLimit(),
                    RerankResult.Status.DEGRADED,
                    ErrorCode.MODEL_TIMEOUT.code().equals(error.code())
                            || ErrorCode.RERANK_TIMEOUT.code().equals(error.code())
                        ? ErrorCode.RERANK_TIMEOUT.code()
                        : ErrorCode.RERANK_FAILED.code(),
                    output,
                    startedAt));
          } catch (RuntimeException error) {
            if (cancellationToken.cancelled()) throw ApiException.cancelled();
            return observed(
                span,
                fallback(
                    input,
                    knowledgeQuestionIds,
                    budget.selectedEvidenceLimit(),
                    RerankResult.Status.DEGRADED,
                    ErrorCode.RERANK_INVALID_RESULT.code(),
                    output,
                    startedAt));
          }
        });
  }

  /** 根据重排结果结束观测阶段。 */
  private RerankResult observed(RagRunTrace.Span span, RerankResult result) {
    span.model(result.modelId(), result.provider(), result.model());
    if (result.status() == RerankResult.Status.DEGRADED) {
      span.degraded(result.selectedCandidates().size(), result.reasonCode());
    } else if (result.status() == RerankResult.Status.DISABLED
        || result.status() == RerankResult.Status.EMPTY) {
      span.skipped(result.selectedCandidates().size(), result.reasonCode());
    } else {
      span.success(result.selectedCandidates().size());
    }
    return result;
  }

  /** 停止重排取消控制使用的虚拟线程执行器。 */
  @PreDestroy
  void close() {
    executor.shutdownNow();
  }

  private CandidateReranker.Output await(
      String standaloneQuestion,
      List<EvidenceCandidate> candidates,
      CancellationToken cancellationToken) {
    Future<CandidateReranker.Output> future =
        executor.submit(() -> reranker.rerank(standaloneQuestion, candidates));
    while (true) {
      if (cancellationToken.cancelled()) {
        future.cancel(true);
        throw ApiException.cancelled();
      }
      try {
        return future.get(CANCELLATION_POLL_MS, TimeUnit.MILLISECONDS);
      } catch (TimeoutException ignored) {
        // 短轮询用于观察会话取消令牌；共享的模型 HTTP 超时是网络边界。
      } catch (InterruptedException error) {
        future.cancel(true);
        Thread.currentThread().interrupt();
        throw ApiException.cancelled();
      } catch (ExecutionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof RuntimeException runtime) throw runtime;
        throw new IllegalStateException(cause);
      }
    }
  }

  private List<RerankDecision> modelRanking(
      List<EvidenceCandidate> candidates, List<CandidateReranker.Score> scores) {
    if (scores.size() != candidates.size()) {
      throw new IllegalArgumentException("Rerank score count mismatch");
    }
    Map<UUID, EvidenceCandidate> byId = new HashMap<>();
    candidates.forEach(candidate -> byId.put(candidate.candidateId(), candidate));
    Map<UUID, Double> scoreById = new HashMap<>();
    for (CandidateReranker.Score score : scores) {
      if (!byId.containsKey(score.candidateId())
          || scoreById.put(score.candidateId(), score.relevanceScore()) != null
          || !Double.isFinite(score.relevanceScore())
          || score.relevanceScore() < 0
          || score.relevanceScore() > 1) {
        throw new IllegalArgumentException("Invalid rerank score mapping");
      }
    }
    if (scoreById.size() != candidates.size()) {
      throw new IllegalArgumentException("Incomplete rerank score mapping");
    }
    List<EvidenceCandidate> ordered = new ArrayList<>(candidates);
    ordered.sort(
        Comparator.<EvidenceCandidate>comparingDouble(
                candidate -> scoreById.get(candidate.candidateId()))
            .reversed()
            .thenComparing(FALLBACK_ORDER));
    List<RerankDecision> result = new ArrayList<>();
    for (int index = 0; index < ordered.size(); index++) {
      EvidenceCandidate candidate = ordered.get(index);
      result.add(
          new RerankDecision(candidate, scoreById.get(candidate.candidateId()), index + 1, false));
    }
    return result;
  }

  private RerankResult fallback(
      List<EvidenceCandidate> input,
      List<String> knowledgeQuestionIds,
      int selectedLimit,
      RerankResult.Status status,
      String reasonCode,
      CandidateReranker.Output output,
      long startedAt) {
    List<EvidenceCandidate> ordered = input.stream().sorted(FALLBACK_ORDER).toList();
    List<RerankDecision> ranking = new ArrayList<>();
    for (int index = 0; index < ordered.size(); index++) {
      ranking.add(new RerankDecision(ordered.get(index), -1, index + 1, false));
    }
    Set<UUID> selectedIds = selectIds(ranking, knowledgeQuestionIds, selectedLimit);
    List<RerankDecision> decisions = markSelected(ranking, selectedIds);
    List<EvidenceCandidate> selected =
        decisions.stream().filter(RerankDecision::selected).map(RerankDecision::candidate).toList();
    return result(selected, decisions, status, reasonCode, output, startedAt);
  }

  private Set<UUID> selectIds(
      List<RerankDecision> ranking, List<String> knowledgeQuestionIds, int limit) {
    Map<UUID, Boolean> selected = new LinkedHashMap<>();
    // 先为每个知识型子问题保留当前排名最高的候选，防止复合问题被单一主题占满。
    for (String questionId : knowledgeQuestionIds) {
      ranking.stream()
          .map(RerankDecision::candidate)
          .filter(candidate -> candidate.sourceSubQuestionIds().contains(questionId))
          .findFirst()
          .ifPresent(candidate -> selected.putIfAbsent(candidate.candidateId(), true));
      if (selected.size() == limit) break;
    }
    for (RerankDecision decision : ranking) {
      if (selected.size() == limit) break;
      selected.putIfAbsent(decision.candidate().candidateId(), true);
    }
    return selected.keySet();
  }

  private List<RerankDecision> markSelected(List<RerankDecision> ranking, Set<UUID> selectedIds) {
    return ranking.stream()
        .map(
            decision ->
                new RerankDecision(
                    decision.candidate(),
                    decision.relevanceScore(),
                    decision.rank(),
                    selectedIds.contains(decision.candidate().candidateId())))
        .toList();
  }

  private List<String> knowledgeQuestionIds(ExecutionResult execution) {
    return execution.subQuestions().stream()
        .filter(item -> item.intent() == IntentType.KNOWLEDGE_RETRIEVAL)
        .filter(item -> item.status() != SubQuestionExecution.Status.FAILED)
        .filter(item -> item.status() != SubQuestionExecution.Status.TIMEOUT)
        .map(SubQuestionExecution::subQuestionId)
        .toList();
  }

  private RerankResult result(
      List<EvidenceCandidate> selected,
      List<RerankDecision> decisions,
      RerankResult.Status status,
      String reasonCode,
      CandidateReranker.Output output,
      long startedAt) {
    RerankResult result =
        new RerankResult(
            selected,
            decisions,
            status,
            reasonCode,
            output == null ? null : output.modelId(),
            output == null ? null : output.provider(),
            output == null ? null : output.model(),
            output == null ? null : output.requestId(),
            output == null ? 0 : output.totalTokens());
    log.info(
        "rerank completed status={} reason={} input={} selected={} modelId={} provider={} totalTokens={} rerankMs={}",
        status,
        reasonCode,
        decisions.size(),
        selected.size(),
        result.modelId(),
        result.provider(),
        result.totalTokens(),
        elapsedMillis(startedAt));
    return result;
  }

  private long elapsedMillis(long startedAt) {
    return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
  }

  /** 兼容根 Trace 入口；内部显式传递父节点上下文。 */
  public RerankResult execute(
      QueryPlan plan,
      ExecutionResult execution,
      List<EvidenceCandidate> deduplicatedCandidates,
      CancellationToken cancellationToken,
      RagRunTrace trace) {
    return execute(plan, execution, deduplicatedCandidates, cancellationToken, trace.context());
  }
}
