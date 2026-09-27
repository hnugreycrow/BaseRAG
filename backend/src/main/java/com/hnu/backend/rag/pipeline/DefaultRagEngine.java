package com.hnu.backend.rag.pipeline;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.observability.RagExecutionMode;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.TraceReasonCatalog;
import com.hnu.backend.observability.trace.AnswerTraceObserver;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.observability.trace.TraceContext;
import com.hnu.backend.rag.api.*;
import com.hnu.backend.rag.clarification.ClarificationDecisionStage;
import com.hnu.backend.rag.generation.AnswerGeneration;
import com.hnu.backend.rag.generation.AnswerResult;
import com.hnu.backend.rag.generation.AssembledPrompt;
import com.hnu.backend.rag.generation.ContextBuilder;
import com.hnu.backend.rag.generation.PromptAssembler;
import com.hnu.backend.rag.retrieval.EvidenceDeduplicator;
import com.hnu.backend.rag.retrieval.EvidenceReranker;
import com.hnu.backend.rag.retrieval.EvidenceRetriever;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 统一编排完整会话与旧单轮流程，不操作会话状态或数据库。 */
@Service
public class DefaultRagEngine implements RagEngine {
  private final RagContextPreparation conversationContextService;
  private final QueryExecution executionStage;
  private final EvidenceDeduplicator deduplicationStage;
  private final EvidenceReranker rerankStage;
  private final PromptAssembler prompts;
  private final AnswerGeneration answers;
  private final EvidenceRetriever retrieval;
  private final ContextBuilder contexts;
  private ClarificationDecisionStage clarification;

  /** 注入执行前消歧阶段；旧独立测试可继续装配不含消歧的引擎。 */
  @Autowired
  public void setClarification(ClarificationDecisionStage stage) {
    this.clarification = stage;
  }

  /** 装配各个可替换阶段。 */
  public DefaultRagEngine(
      RagContextPreparation context,
      QueryExecution executionStage,
      EvidenceDeduplicator deduplicationStage,
      EvidenceReranker rerankStage,
      PromptAssembler prompts,
      AnswerGeneration answers,
      EvidenceRetriever retrieval,
      ContextBuilder contexts) {
    this.conversationContextService = context;
    this.executionStage = executionStage;
    this.deduplicationStage = deduplicationStage;
    this.rerankStage = rerankStage;
    this.prompts = prompts;
    this.answers = answers;
    this.retrieval = retrieval;
    this.contexts = contexts;
  }

  @Override
  public RagExecutionControl newControl() {
    return answers.newControl();
  }

  @Override
  public RagResult execute(
      RagRequest request, RagObserver observer, RagExecutionControl control, RagRunTrace trace) {
    ensureActive(observer, control);
    if (request.mode() == RagRequest.Mode.LEGACY) {
      var hits =
          request.knowledgeBaseIds() == null
              ? retrieval.retrieve(request.ownerId(), request.question())
              : retrieval.retrieve(
                  request.ownerId(), request.question(), request.knowledgeBaseIds());
      ensureActive(observer, control);
      var prompt = prompts.assembleLegacy(request.question(), contexts.build(hits));
      observer.prepared(request.question(), prompt.sources());
      ensureActive(observer, control);
      return result(answers.execute(prompt, observer, control));
    }
    var initial = conversationContextService.prepare(request, trace, control::cancelled);
    ensureActive(observer, control);
    var decision =
        clarification == null
            ? null
            : clarification.execute(initial, request.clarification(), trace);
    ensureActive(observer, control);
    if (decision != null && decision.invalidated()) {
      observer.prepared(null, List.of());
      return new RagResult("意图或知识库配置已改变，无法继续本次选择。请重新提出原问题。", List.of(), List.of(), null);
    }
    if (decision != null && decision.context() != null && !decision.context().pending().isEmpty()) {
      trace.executionMode(RagExecutionMode.WAITING_CLARIFICATION);
      observer.prepared(null, List.of());
      String question =
          decision.context().plan().subQuestions().stream()
              .filter(
                  item -> item.id().equals(decision.context().pending().getFirst().subQuestionId()))
              .map(SubQuestion::question)
              .findFirst()
              .orElse(decision.context().plan().standaloneQuestion());
      return new RagResult(
          "关于“" + question + "”，你想了解哪一项？请选择下方意图，或补充说明。",
          List.of(),
          List.of(),
          null,
          decision.context());
    }
    var prepared =
        decision == null
            ? initial
            : new RagContextPreparation.PreparedContext(
                initial.memory(), initial.queryPlan(), decision.routing());
    ensureActive(observer, control);
    String standaloneQuestion = prepared.queryPlan().standaloneQuestion();
    if (prepared.routingPlan().systemChatOnly()) {
      trace.executionMode(RagExecutionMode.SYSTEM_CHAT);
      return answerSystemChat(request, observer, control, trace, prepared);
    }
    trace.executionMode(RagExecutionMode.FULL_PIPELINE);
    var retrieved =
        executionStage.executeRetrieval(
            request.ownerId(),
            prepared.queryPlan(),
            prepared.routingPlan(),
            request.knowledgeBaseIds(),
            control::cancelled,
            trace.context());
    AssembledPrompt prompt =
        trace
            .context()
            .execute(
                RagStageName.EVIDENCE,
                null,
                null,
                evidenceSpan -> {
                  TraceContext evidence = evidenceSpan.context();
                  var execution = executionStage.merge(retrieved, evidence);
                  ensureActive(observer, control);
                  var deduplicated =
                      evidence.execute(
                          RagStageName.DEDUPLICATION,
                          null,
                          execution.candidates().size(),
                          span -> {
                            var result =
                                deduplicationStage.execute(
                                    execution.candidates(), execution.budget());
                            span.success(result.candidates().size());
                            return result;
                          });
                  ensureActive(observer, control);
                  var reranked =
                      rerankStage.execute(
                          prepared.queryPlan(),
                          execution,
                          deduplicated.candidates(),
                          control::cancelled,
                          evidence);
                  ensureActive(observer, control);
                  int promptInputCount =
                      reranked.selectedCandidates().size()
                          + (int)
                              execution.subQuestions().stream()
                                  .filter(result -> result.toolObservation() != null)
                                  .count();
                  AssembledPrompt assembledPrompt =
                      evidence.execute(
                          RagStageName.PROMPT_ASSEMBLY,
                          null,
                          promptInputCount,
                          promptSpan -> {
                            AssembledPrompt assembled =
                                prompts.assemblePipeline(
                                    prepared.memory(),
                                    request.question(),
                                    prepared.queryPlan(),
                                    prepared.routingPlan(),
                                    execution,
                                    reranked.selectedCandidates());
                            promptSpan.success(
                                reranked.selectedCandidates().size()
                                    + assembled.toolReferenceIds().size());
                            return assembled;
                          });
                  trace.evidenceCount(reranked.selectedCandidates().size());
                  return assembledPrompt;
                });
    // sources 为文档数；evidence_count 仍是进入 Prompt 的原始证据分块数。
    observer.prepared(standaloneQuestion, prompt.sources());
    ensureActive(observer, control);
    AnswerResult answer = generateAnswer(request, observer, control, trace, prompt);
    return result(answer);
  }

  private RagResult answerSystemChat(
      RagRequest request,
      RagObserver observer,
      RagExecutionControl control,
      RagRunTrace trace,
      RagContextPreparation.PreparedContext prepared) {
    observer.prepared(null, List.of());
    ensureActive(observer, control);
    trace
        .context()
        .execute(
            RagStageName.RETRIEVAL,
            null,
            null,
            span -> {
              prepared
                  .queryPlan()
                  .subQuestions()
                  .forEach(
                      question ->
                          span.context()
                              .skipped(
                                  RagStageName.SUBQUESTION_EXECUTION,
                                  question.id(),
                                  TraceReasonCatalog.SYSTEM_CHAT_ROUTED.code()));
              span.skipped(0, TraceReasonCatalog.SYSTEM_CHAT.code());
              return null;
            });
    AssembledPrompt prompt =
        trace
            .context()
            .execute(
                RagStageName.EVIDENCE,
                null,
                null,
                span -> {
                  TraceContext evidence = span.context();
                  evidence.skipped(
                      RagStageName.CANDIDATE_MERGE, null, TraceReasonCatalog.SYSTEM_CHAT.code());
                  evidence.skipped(
                      RagStageName.DEDUPLICATION, null, TraceReasonCatalog.SYSTEM_CHAT.code());
                  evidence.skipped(
                      RagStageName.RERANK, null, TraceReasonCatalog.SYSTEM_CHAT.code());
                  return evidence.execute(
                      RagStageName.PROMPT_ASSEMBLY,
                      null,
                      0,
                      promptSpan -> {
                        AssembledPrompt assembled =
                            prompts.assembleSystemChat(
                                prepared.memory(),
                                request.question(),
                                prepared.queryPlan(),
                                prepared.routingPlan());
                        promptSpan.success(0);
                        return assembled;
                      });
                });
    trace.evidenceCount(0);
    AnswerResult answer = generateAnswer(request, observer, control, trace, prompt);
    return result(answer);
  }

  private AnswerResult generateAnswer(
      RagRequest request,
      RagObserver observer,
      RagExecutionControl control,
      RagRunTrace trace,
      AssembledPrompt prompt) {
    return trace
        .context()
        .execute(
            RagStageName.ANSWER,
            null,
            1,
            span -> {
              var answerTrace =
                  new AnswerTraceObserver(
                      span.context(),
                      observer,
                      observer::attemptId,
                      observer::attemptIndex,
                      control::cancelled);
              observer.traceReady(answerTrace);
              try {
                return answers.execute(prompt, answerTrace, control, request.thinkingEnabled());
              } catch (RuntimeException error) {
                if (control.cancelled()) {
                  throw ApiException.cancelled();
                }
                throw error;
              }
            });
  }

  private static void ensureActive(RagObserver observer, RagExecutionControl control) {
    control.throwIfCancelled();
    observer.ensureActive();
  }

  private static RagResult result(AnswerResult answer) {
    return new RagResult(
        answer.content(), answer.sources(), answer.citations(), answer.generation());
  }
}
