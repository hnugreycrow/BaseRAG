package com.hnu.backend.rag.pipeline;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.TraceReasonCatalog;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.observability.trace.TraceContext;
import com.hnu.backend.rag.config.RagProperties;
import com.hnu.backend.rag.mcp.McpToolCall;
import com.hnu.backend.rag.mcp.McpToolExecutor;
import com.hnu.backend.rag.mcp.ToolObservation;
import com.hnu.backend.rag.retrieval.CandidateMerge;
import com.hnu.backend.rag.retrieval.EvidenceCandidate;
import com.hnu.backend.rag.retrieval.RetrievalService;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 按子问题路由并发执行检索或 MCP 调用，再生成可供重排的全局候选池。 */
@Component
public class ExecutionStage {

  private final RetrievalService retrievalService;
  private final McpToolExecutor tools;
  private final CandidateMerge candidateMerge;
  private final RagProperties config;
  private final SubQuestionScheduler scheduler;

  /**
   * 创建子问题执行阶段。
   *
   * @param retrievalService 用户隔离的检索服务
   * @param tools MCP 工具执行器
   * @param candidateMerge 候选合并器
   * @param config RAG 预算配置
   */
  @Autowired
  public ExecutionStage(
      RetrievalService retrievalService,
      McpToolExecutor tools,
      CandidateMerge candidateMerge,
      RagProperties config) {
    this(
        retrievalService,
        tools,
        candidateMerge,
        config,
        Executors.newFixedThreadPool(
            config.getPipeline().getMaxSubQuestions(), Thread.ofVirtual().factory()));
  }

  /** 测试可传入可控执行器验证排队边界；生产并发数仍由子问题预算决定。 */
  ExecutionStage(
      RetrievalService retrievalService,
      McpToolExecutor tools,
      CandidateMerge candidateMerge,
      RagProperties config,
      ExecutorService executor) {
    this.retrievalService = retrievalService;
    this.tools = tools;
    this.candidateMerge = candidateMerge;
    this.config = config;
    this.scheduler = new SubQuestionScheduler(executor);
  }

  /**
   * 按路由执行全部子问题，默认不限制知识库范围且不提供外部取消信号。
   *
   * @param ownerId 所属用户标识
   * @param plan 查询计划
   * @param routing 与查询计划对齐的路由计划
   * @return 执行结果
   */
  public ExecutionResult execute(UUID ownerId, QueryPlan plan, RoutingPlan routing) {
    return execute(ownerId, plan, routing, null, CancellationToken.NONE);
  }

  /**
   * 在用户所有权范围内并发执行检索或工具子问题。
   *
   * @param ownerId 所属用户标识；会被显式传入异步检索任务
   * @param plan 查询计划
   * @param routing 路由计划
   * @param knowledgeBaseIds 可选知识库范围
   * @param cancellationToken 取消信号
   * @return 聚合后的执行结果
   */
  public ExecutionResult execute(
      UUID ownerId,
      QueryPlan plan,
      RoutingPlan routing,
      List<UUID> knowledgeBaseIds,
      CancellationToken cancellationToken) {
    return execute(ownerId, plan, routing, knowledgeBaseIds, cancellationToken, RagRunTrace.noop());
  }

  /**
   * 并发执行子问题并显式向子线程传播 Trace。
   *
   * @param ownerId 所属用户标识
   * @param plan 查询计划
   * @param routing 路由计划
   * @param knowledgeBaseIds 可选知识库范围
   * @param cancellationToken 取消信号
   * @param trace 当前问答 Trace
   * @return 聚合后的执行结果
   */
  public ExecutionResult execute(
      UUID ownerId,
      QueryPlan plan,
      RoutingPlan routing,
      List<UUID> knowledgeBaseIds,
      CancellationToken cancellationToken,
      RagRunTrace trace) {
    ExecutionResult retrieved =
        executeRetrieval(
            ownerId, plan, routing, knowledgeBaseIds, cancellationToken, trace.context());
    return trace
        .context()
        .execute(RagStageName.EVIDENCE, null, null, span -> merge(retrieved, span.context()));
  }

  /** 执行检索范围；候选合并由调用方的证据整理阶段负责。 */
  public ExecutionResult executeRetrieval(
      UUID ownerId,
      QueryPlan plan,
      RoutingPlan routing,
      List<UUID> knowledgeBaseIds,
      CancellationToken cancellationToken,
      TraceContext trace) {
    return trace.execute(
        RagStageName.RETRIEVAL,
        null,
        plan.subQuestions().size(),
        span ->
            retrieve(ownerId, plan, routing, knowledgeBaseIds, cancellationToken, span.context()));
  }

  /** 在检索父节点下提交独立任务，并保留现有超时预算口径。 */
  private ExecutionResult retrieve(
      UUID ownerId,
      QueryPlan plan,
      RoutingPlan routing,
      List<UUID> knowledgeBaseIds,
      CancellationToken cancellationToken,
      TraceContext trace) {
    validateAlignment(plan, routing);
    cancellationToken.throwIfCancelled();
    RagBudgetSnapshot snapshot = RagBudgetSnapshot.from(config);
    if (plan.subQuestions().size() > snapshot.maxSubQuestions()) {
      throw new IllegalArgumentException("Query plan exceeds max sub-questions");
    }

    List<SubQuestionScheduler.Task> tasks = new ArrayList<>();
    List<SubQuestionExecution> results = new ArrayList<>();
    for (int index = 0; index < plan.subQuestions().size(); index++) {
      SubQuestion question = plan.subQuestions().get(index);
      IntentRoute route = routing.routes().get(index);
      if (route.intent() == IntentType.SYSTEM_CHAT) {
        trace.skipped(
            RagStageName.SUBQUESTION_EXECUTION,
            question.id(),
            TraceReasonCatalog.SYSTEM_CHAT_ROUTED.code());
        results.add(
            new SubQuestionExecution(
                question.id(),
                route.intent(),
                SubQuestionExecution.Status.SKIPPED,
                List.of(),
                null,
                TraceReasonCatalog.SYSTEM_CHAT_ROUTED.code(),
                0));
        continue;
      }
      // 向量通道预算由检索服务仅在数据库召回时扣除，Embedding 不占用该预算。
      long timeoutMs =
          route.intent() == IntentType.MCP_TOOL ? config.getPipeline().getMcp().getTimeoutMs() : 0;
      tasks.add(
          new SubQuestionScheduler.Task(
              question,
              route,
              timeoutMs,
              (taskTrace, submittedAt) ->
                  executeOne(
                      ownerId,
                      question,
                      route,
                      knowledgeBaseIds,
                      snapshot,
                      cancellationToken,
                      taskTrace,
                      submittedAt)));
    }
    results.addAll(scheduler.execute(tasks, cancellationToken, trace));
    cancellationToken.throwIfCancelled();

    results.sort(
        java.util.Comparator.comparingInt(
            result -> indexOf(plan.subQuestions(), result.subQuestionId())));
    List<EvidenceCandidate> allCandidates =
        results.stream().flatMap(result -> result.candidates().stream()).toList();
    return new ExecutionResult(allCandidates, results, snapshot);
  }

  /** 在证据整理父节点下合并候选，保留原来的召回数量与排序规则。 */
  public ExecutionResult merge(ExecutionResult execution, TraceContext trace) {
    List<String> knowledgeQuestionIds =
        execution.subQuestions().stream()
            .filter(result -> result.intent() == IntentType.KNOWLEDGE_RETRIEVAL)
            .map(SubQuestionExecution::subQuestionId)
            .toList();
    List<EvidenceCandidate> merged =
        trace.execute(
            RagStageName.CANDIDATE_MERGE,
            null,
            execution.candidates().size(),
            span -> {
              var candidates =
                  candidateMerge.mergeAndSelect(
                      execution.candidates(),
                      knowledgeQuestionIds,
                      execution.budget().rerankInputLimit());
              span.success(candidates.size());
              return candidates;
            });
    trace.candidateCount(merged.size());
    return new ExecutionResult(merged, execution.subQuestions(), execution.budget());
  }

  /** 停止并发子问题执行器。 */
  @PreDestroy
  void close() {
    scheduler.close();
  }

  /** 执行并观测单个子问题。 */
  private SubQuestionExecution executeOne(
      UUID ownerId,
      SubQuestion question,
      IntentRoute route,
      List<UUID> knowledgeBaseIds,
      RagBudgetSnapshot snapshot,
      CancellationToken cancellationToken,
      TraceContext trace,
      long startedAt) {
    cancellationToken.throwIfCancelled();
    try {
      if (route.intent() == IntentType.KNOWLEDGE_RETRIEVAL) {
        List<EvidenceCandidate> candidates =
            route.knowledgeBaseIds() != null
                ? retrievalService.retrieveDirectedCandidates(
                    ownerId,
                    question.id(),
                    question.question(),
                    route.knowledgeBaseIds(),
                    snapshot.forSubQuestion(question.id()),
                    cancellationToken,
                    trace)
                : trace.enabled()
                    ? retrievalService.retrieveCandidates(
                        ownerId,
                        question.id(),
                        question.question(),
                        knowledgeBaseIds,
                        snapshot.forSubQuestion(question.id()),
                        cancellationToken,
                        trace)
                    : retrievalService.retrieveCandidates(
                        ownerId,
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
      return trace.execute(
          RagStageName.MCP_EXECUTION,
          question.id(),
          1,
          toolSpan -> {
            ToolObservation observation =
                tools.execute(new McpToolCall(route.toolHint(), route.toolArguments()));
            SubQuestionExecution.Status status =
                observation.status() == ToolObservation.Status.SUCCESS
                    ? SubQuestionExecution.Status.SUCCESS
                    : SubQuestionExecution.Status.FAILED;
            if (status == SubQuestionExecution.Status.SUCCESS) {
              toolSpan.success(1);
            } else {
              toolSpan.degraded(0, observation.reasonCode());
            }
            return result(
                question,
                route,
                status,
                List.of(),
                observation,
                observation.reasonCode(),
                startedAt);
          });
    } catch (ApiException error) {
      if (ErrorCode.GENERATION_CANCELLED.code().equals(error.code())) {
        throw error;
      }
      return result(
          question,
          route,
          ErrorCode.SUBQUESTION_TIMEOUT.code().equals(error.code())
              ? SubQuestionExecution.Status.TIMEOUT
              : SubQuestionExecution.Status.FAILED,
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
          ErrorCode.EXECUTION_FAILED.code(),
          startedAt);
    }
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
      if (questions.get(index).id().equals(subQuestionId)) {
        return index;
      }
    }
    return Integer.MAX_VALUE;
  }

  private long elapsedMillis(long startedAt) {
    return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
  }
}
