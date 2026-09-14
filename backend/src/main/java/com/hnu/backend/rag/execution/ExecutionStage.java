package com.hnu.backend.rag.execution;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.rag.mcp.McpToolCall;
import com.hnu.backend.rag.mcp.McpToolExecutor;
import com.hnu.backend.rag.mcp.ToolObservation;
import com.hnu.backend.rag.planning.QueryPlan;
import com.hnu.backend.rag.planning.SubQuestion;
import com.hnu.backend.rag.retrieval.CandidateMerge;
import com.hnu.backend.rag.retrieval.EvidenceCandidate;
import com.hnu.backend.rag.retrieval.RetrievalService;
import com.hnu.backend.rag.routing.IntentRoute;
import com.hnu.backend.rag.routing.IntentType;
import com.hnu.backend.rag.routing.RoutingPlan;
import com.hnu.backend.shared.error.ApiException;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
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

/** 按子问题路由并发执行检索或 MCP 调用，再生成可供重排的全局候选池。 */
@Component
public class ExecutionStage {
  private static final Logger log = LoggerFactory.getLogger(ExecutionStage.class);
  private static final long CANCELLATION_POLL_MS = 50;

  private final RetrievalService retrieval;
  private final McpToolExecutor tools;
  private final CandidateMerge candidateMerge;
  private final RagProperties config;
  private final ExecutorService executor;

  public ExecutionStage(
      RetrievalService retrieval,
      McpToolExecutor tools,
      CandidateMerge candidateMerge,
      RagProperties config) {
    this.retrieval = retrieval;
    this.tools = tools;
    this.candidateMerge = candidateMerge;
    this.config = config;
    // 子问题数量本身就是当前阶段的并发上限，无需再引入一套尚未使用的线程预算配置。
    this.executor =
        Executors.newFixedThreadPool(
            config.getPipeline().getMaxSubQuestions(), Thread.ofVirtual().factory());
  }

  public ExecutionResult execute(QueryPlan plan, RoutingPlan routing) {
    return execute(plan, routing, null, CancellationToken.NONE);
  }

  public ExecutionResult execute(
      QueryPlan plan,
      RoutingPlan routing,
      List<UUID> knowledgeBaseIds,
      CancellationToken cancellationToken) {
    validateAlignment(plan, routing);
    cancellationToken.throwIfCancelled();
    RagBudgetSnapshot snapshot = RagBudgetSnapshot.from(config);
    if (plan.subQuestions().size() > snapshot.maxSubQuestions()) {
      throw new IllegalArgumentException("Query plan exceeds max sub-questions");
    }

    List<TaskHandle> handles = new ArrayList<>();
    List<SubQuestionExecution> results = new ArrayList<>();
    for (int index = 0; index < plan.subQuestions().size(); index++) {
      SubQuestion question = plan.subQuestions().get(index);
      IntentRoute route = routing.routes().get(index);
      if (route.intent() == IntentType.SYSTEM_CHAT) {
        results.add(
            new SubQuestionExecution(
                question.id(),
                route.intent(),
                SubQuestionExecution.Status.SKIPPED,
                List.of(),
                null,
                "SYSTEM_CHAT_ROUTED",
                0));
        continue;
      }
      long timeoutMs = timeoutFor(route, snapshot);
      Future<SubQuestionExecution> future =
          executor.submit(
              () -> executeOne(question, route, knowledgeBaseIds, snapshot, cancellationToken));
      handles.add(new TaskHandle(question, route, future, System.nanoTime(), timeoutMs));
    }

    try {
      for (TaskHandle handle : handles) results.add(await(handle, cancellationToken));
    } catch (RuntimeException error) {
      handles.forEach(handle -> handle.future().cancel(true));
      throw error;
    }
    cancellationToken.throwIfCancelled();

    results.sort(
        java.util.Comparator.comparingInt(
            result -> indexOf(plan.subQuestions(), result.subQuestionId())));
    List<String> knowledgeQuestionIds =
        results.stream()
            .filter(result -> result.intent() == IntentType.KNOWLEDGE_RETRIEVAL)
            .map(SubQuestionExecution::subQuestionId)
            .toList();
    List<EvidenceCandidate> allCandidates =
        results.stream().flatMap(result -> result.candidates().stream()).toList();
    List<EvidenceCandidate> merged =
        candidateMerge.mergeAndSelect(
            allCandidates, knowledgeQuestionIds, snapshot.rerankCandidateLimit());
    log.info(
        "execution completed subQuestions={} successful={} candidatesBeforeMerge={} candidatesAfterMerge={}",
        results.size(),
        results.stream()
            .filter(result -> result.status() == SubQuestionExecution.Status.SUCCESS)
            .count(),
        allCandidates.size(),
        merged.size());
    return new ExecutionResult(merged, results, snapshot);
  }

  /** 在重排阶段尚未接入时，按同一覆盖规则选出当前可进入回答上下文的候选。 */
  public List<EvidenceCandidate> selectForAnswer(ExecutionResult result) {
    List<String> knowledgeQuestionIds =
        result.subQuestions().stream()
            .filter(execution -> execution.intent() == IntentType.KNOWLEDGE_RETRIEVAL)
            .map(SubQuestionExecution::subQuestionId)
            .toList();
    return candidateMerge.mergeAndSelect(
        result.candidates(), knowledgeQuestionIds, result.budget().defaultTopK());
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }

  private SubQuestionExecution executeOne(
      SubQuestion question,
      IntentRoute route,
      List<UUID> knowledgeBaseIds,
      RagBudgetSnapshot snapshot,
      CancellationToken cancellationToken) {
    long startedAt = System.nanoTime();
    cancellationToken.throwIfCancelled();
    try {
      if (route.intent() == IntentType.KNOWLEDGE_RETRIEVAL) {
        List<EvidenceCandidate> candidates =
            retrieval.retrieveCandidates(
                question.id(),
                question.question(),
                knowledgeBaseIds,
                snapshot.forSubQuestion(question.id()),
                cancellationToken);
        return result(
            question,
            route,
            candidates.isEmpty()
                ? SubQuestionExecution.Status.EMPTY
                : SubQuestionExecution.Status.SUCCESS,
            candidates,
            null,
            candidates.isEmpty() ? "NO_CANDIDATES" : "RETRIEVAL_COMPLETED",
            startedAt);
      }
      ToolObservation observation =
          tools.execute(new McpToolCall(route.toolHint(), route.toolArguments()));
      SubQuestionExecution.Status status =
          observation.status() == ToolObservation.Status.SUCCESS
              ? SubQuestionExecution.Status.SUCCESS
              : SubQuestionExecution.Status.FAILED;
      return result(
          question, route, status, List.of(), observation, observation.reasonCode(), startedAt);
    } catch (ApiException error) {
      if ("GENERATION_CANCELLED".equals(error.code())) throw error;
      return result(
          question,
          route,
          SubQuestionExecution.Status.FAILED,
          List.of(),
          null,
          error.code(),
          startedAt);
    } catch (RuntimeException error) {
      return result(
          question,
          route,
          SubQuestionExecution.Status.FAILED,
          List.of(),
          null,
          "EXECUTION_FAILED",
          startedAt);
    }
  }

  private SubQuestionExecution await(TaskHandle handle, CancellationToken cancellationToken) {
    long deadline = handle.startedAt() + TimeUnit.MILLISECONDS.toNanos(handle.timeoutMs());
    while (true) {
      cancellationToken.throwIfCancelled();
      try {
        // 先读取已经完成的结果，避免等待前序任务后把早已结束的后序任务误判为超时。
        if (handle.future().isDone()) return enforceTimeout(handle, handle.future().get());
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          handle.future().cancel(true);
          return result(
              handle.question(),
              handle.route(),
              SubQuestionExecution.Status.TIMEOUT,
              List.of(),
              null,
              "SUBQUESTION_TIMEOUT",
              handle.startedAt());
        }
        long waitNanos = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(CANCELLATION_POLL_MS));
        return enforceTimeout(handle, handle.future().get(waitNanos, TimeUnit.NANOSECONDS));
      } catch (TimeoutException ignored) {
        // 短轮询让仅改变取消令牌、未中断父线程的调用方也能快速停止所有子任务。
      } catch (InterruptedException error) {
        handle.future().cancel(true);
        Thread.currentThread().interrupt();
        throw ApiException.cancelled();
      } catch (ExecutionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof RuntimeException runtime) throw runtime;
        throw new IllegalStateException(cause);
      }
    }
  }

  private SubQuestionExecution enforceTimeout(TaskHandle handle, SubQuestionExecution completed) {
    if (completed.elapsedMs() <= handle.timeoutMs()) return completed;
    return result(
        handle.question(),
        handle.route(),
        SubQuestionExecution.Status.TIMEOUT,
        List.of(),
        null,
        "SUBQUESTION_TIMEOUT",
        handle.startedAt());
  }

  private SubQuestionExecution result(
      SubQuestion question,
      IntentRoute route,
      SubQuestionExecution.Status status,
      List<EvidenceCandidate> candidates,
      ToolObservation observation,
      String reasonCode,
      long startedAt) {
    return new SubQuestionExecution(
        question.id(),
        route.intent(),
        status,
        candidates,
        observation,
        reasonCode,
        elapsedMillis(startedAt));
  }

  private long timeoutFor(IntentRoute route, RagBudgetSnapshot snapshot) {
    return route.intent() == IntentType.MCP_TOOL
        ? config.getPipeline().getMcp().getTimeoutMs()
        : snapshot.channelTimeoutMs();
  }

  private void validateAlignment(QueryPlan plan, RoutingPlan routing) {
    if (plan.subQuestions().size() != routing.routes().size()) {
      throw new IllegalArgumentException("Query plan and routing plan size mismatch");
    }
    for (int index = 0; index < plan.subQuestions().size(); index++) {
      if (!plan.subQuestions()
          .get(index)
          .id()
          .equals(routing.routes().get(index).subQuestionId())) {
        throw new IllegalArgumentException("Query plan and routing plan order mismatch");
      }
    }
  }

  private int indexOf(List<SubQuestion> questions, String subQuestionId) {
    for (int index = 0; index < questions.size(); index++) {
      if (questions.get(index).id().equals(subQuestionId)) return index;
    }
    return Integer.MAX_VALUE;
  }

  private long elapsedMillis(long startedAt) {
    return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
  }

  /**
   * 已提交子任务及其独立计时边界。
   *
   * @param question 子任务对应的规划问题
   * @param route 决定任务执行检索还是工具调用的安全路由
   * @param future 用于等待、超时中断和总取消传播的并发句柄
   * @param startedAt 提交任务时的单调时钟值，排队时间也计入预算
   * @param timeoutMs 此任务允许占用的最长时间
   */
  private record TaskHandle(
      SubQuestion question,
      IntentRoute route,
      Future<SubQuestionExecution> future,
      long startedAt,
      long timeoutMs) {}
}
