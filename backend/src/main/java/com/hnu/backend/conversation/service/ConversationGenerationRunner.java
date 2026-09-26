package com.hnu.backend.conversation.service;

import com.hnu.backend.configuration.ConversationProperties;
import com.hnu.backend.conversation.entity.GenerationAttempt;
import com.hnu.backend.conversation.entity.GenerationAttemptStatus;
import com.hnu.backend.conversation.mapper.GenerationAttemptMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.conversation.vo.ConversationStreamEvents;
import com.hnu.backend.conversation.vo.ConversationStreamEvents.Kind;
import com.hnu.backend.observability.RagExecutionMode;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.TraceReasonCatalog;
import com.hnu.backend.observability.trace.AnswerTraceObserver;
import com.hnu.backend.observability.trace.TraceContext;
import com.hnu.backend.rag.answer.AnswerGenerator;
import com.hnu.backend.rag.answer.AnswerResult;
import com.hnu.backend.rag.answer.AnswerStage;
import com.hnu.backend.rag.deduplication.DeduplicationStage;
import com.hnu.backend.rag.execution.ExecutionStage;
import com.hnu.backend.rag.prompt.AssembledPrompt;
import com.hnu.backend.rag.prompt.PromptAssemblyStage;
import com.hnu.backend.rag.rerank.RerankStage;
import com.hnu.backend.rag.vo.ModelInfoResponse;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import com.hnu.backend.shared.error.SafeExceptionLog;
import com.hnu.backend.shared.json.JsonCodecs;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** 执行单次回答流水线，并把中间输出映射为检查点与 SSE 增量。 */
@Service
public class ConversationGenerationRunner {
  private static final Logger log = LoggerFactory.getLogger(ConversationGenerationRunner.class);
  private final ConversationContextService conversationContextService;
  private final ExecutionStage executionStage;
  private final DeduplicationStage deduplicationStage;
  private final RerankStage rerankStage;
  private final PromptAssemblyStage prompts;
  private final AnswerStage answers;
  private final MessageMapper messageMapper;
  private final GenerationAttemptMapper generationAttemptMapper;
  private final ConversationProperties config;
  private final JsonMapper json = JsonCodecs.snapshots();

  /**
   * 创建回答流水线执行器。
   *
   * @param conversationContextService 会话记忆、规划和路由准备服务
   * @param executionStage 子问题检索与候选合并阶段
   * @param deduplicationStage 证据去重阶段
   * @param rerankStage 证据重排阶段
   * @param prompts 提示词组装阶段
   * @param answers 流式回答与引用校验阶段
   * @param messageMapper 回答检查点持久化接口
   * @param generationAttemptMapper 模型尝试持久化接口
   * @param config 流式检查点间隔配置
   */
  public ConversationGenerationRunner(
      ConversationContextService conversationContextService,
      ExecutionStage executionStage,
      DeduplicationStage deduplicationStage,
      RerankStage rerankStage,
      PromptAssemblyStage prompts,
      AnswerStage answers,
      MessageMapper messageMapper,
      GenerationAttemptMapper generationAttemptMapper,
      ConversationProperties config) {
    this.conversationContextService = conversationContextService;
    this.executionStage = executionStage;
    this.deduplicationStage = deduplicationStage;
    this.rerankStage = rerankStage;
    this.prompts = prompts;
    this.answers = answers;
    this.messageMapper = messageMapper;
    this.generationAttemptMapper = generationAttemptMapper;
    this.config = config;
  }

  /** 执行器在竞争终态时调用的持久化与流关闭回调。 */
  interface TerminalCallbacks {
    void completed(
        ActiveGeneration active,
        String content,
        List<String> citations,
        AnswerGenerator.Generation generation);

    void cancelled(ActiveGeneration active);

    void failed(ActiveGeneration active, String code, String message);
  }

  /**
   * 执行记忆、规划、路由、检索、去重、重排、提示词组装和流式回答全链路。
   *
   * @param active 活动生成状态
   */
  void generate(ActiveGeneration active, TerminalCallbacks callbacks) {
    try {
      active.start();
      if (active.cancelled()) {
        throw ApiException.cancelled();
      }
      active
          .channel()
          .send(
              Kind.STARTED,
              ConversationStreamEvents.started(
                  active.conversation().getId(),
                  active.user().getId(),
                  active.generationId(),
                  active.user().getTurnIndex(),
                  active.variantIndex()));
      var prepared =
          conversationContextService.prepare(
              active.conversation(),
              active.user().getTurnIndex(),
              active.user().getContent(),
              active.trace());
      ensureNotCancelled(active);
      String standaloneQuestion = prepared.queryPlan().standaloneQuestion();
      if (prepared.routingPlan().systemChatOnly()) {
        active.trace().executionMode(RagExecutionMode.SYSTEM_CHAT);
        answerSystemChat(active, prepared, callbacks);
        return;
      }
      active.trace().executionMode(RagExecutionMode.FULL_PIPELINE);
      var retrieved =
          executionStage.executeRetrieval(
              active.ownerId(),
              prepared.queryPlan(),
              prepared.routingPlan(),
              null,
              active::cancelled,
              active.trace().context());
      AssembledPrompt prompt =
          active
              .trace()
              .context()
              .execute(
                  RagStageName.EVIDENCE,
                  null,
                  null,
                  evidenceSpan -> {
                    TraceContext evidence = evidenceSpan.context();
                    var execution = executionStage.merge(retrieved, evidence);
                    ensureNotCancelled(active);
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
                    ensureNotCancelled(active);
                    var reranked =
                        rerankStage.execute(
                            prepared.queryPlan(),
                            execution,
                            deduplicated.candidates(),
                            active::cancelled,
                            evidence);
                    ensureNotCancelled(active);
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
                                      active.user().getContent(),
                                      prepared.queryPlan(),
                                      prepared.routingPlan(),
                                      execution,
                                      reranked.selectedCandidates());
                              promptSpan.success(
                                  reranked.selectedCandidates().size()
                                      + assembled.toolReferenceIds().size());
                              return assembled;
                            });
                    active.trace().evidenceCount(reranked.selectedCandidates().size());
                    return assembledPrompt;
                  });
      // sources 为文档数；evidence_count 仍是进入 Prompt 的原始证据分块数。
      messageMapper.prepare(
          active.ownerId(),
          active.generationId(),
          standaloneQuestion,
          json.writeValueAsString(prompt.sources()));
      ensureNotCancelled(active);
      AnswerResult answer = generateAnswer(active, prompt);
      callbacks.completed(active, answer.content(), answer.citations(), answer.generation());
    } catch (ApiException e) {
      if (active.cancelled() || ErrorCode.GENERATION_CANCELLED.code().equals(e.code())) {
        callbacks.cancelled(active);
      } else {
        callbacks.failed(active, e.code(), e.getMessage());
      }
    } catch (RuntimeException e) {
      if (active.cancelled()) {
        callbacks.cancelled(active);
        return;
      }
      log.error(
          "conversation={} generation={} code={} exceptionType={} safeStack={}",
          active.conversation().getId(),
          active.generationId(),
          ErrorCode.INTERNAL_ERROR.code(),
          e.getClass().getSimpleName(),
          SafeExceptionLog.render(e));
      callbacks.failed(
          active, ErrorCode.INTERNAL_ERROR.code(), ErrorCode.INTERNAL_ERROR.defaultMessage());
    }
  }

  /**
   * 为纯系统闲聊路由组装无证据提示词并流式回答。
   *
   * @param active 活动生成状态
   * @param prepared 已准备的记忆、问题计划和路由
   */
  private void answerSystemChat(
      ActiveGeneration active,
      ConversationContextService.PreparedContext prepared,
      TerminalCallbacks callbacks) {
    messageMapper.prepare(active.ownerId(), active.generationId(), null, "[]");
    ensureNotCancelled(active);
    active
        .trace()
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
        active
            .trace()
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
                                active.user().getContent(),
                                prepared.queryPlan(),
                                prepared.routingPlan());
                        promptSpan.success(0);
                        return assembled;
                      });
                });
    active.trace().evidenceCount(0);
    AnswerResult answer = generateAnswer(active, prompt);
    callbacks.completed(active, answer.content(), answer.citations(), answer.generation());
  }

  /** 回答父阶段涵盖模型尝试和引用校验，保存结果在其结束后单独计时。 */
  private AnswerResult generateAnswer(ActiveGeneration active, AssembledPrompt prompt) {
    return active
        .trace()
        .context()
        .execute(
            RagStageName.ANSWER,
            null,
            1,
            span -> {
              active.observeAnswer(
                  new AnswerTraceObserver(
                      span.context(),
                      new ConversationAnswerObserver(active),
                      active::currentAttemptId,
                      active::attemptIndex,
                      active::cancelled));
              try {
                return answers.execute(
                    prompt, active.answerTrace(), active.control(), active.thinkingEnabled());
              } catch (RuntimeException error) {
                if (active.cancelled()) {
                  throw ApiException.cancelled();
                }
                throw error;
              }
            });
  }

  /**
   * 在各阶段边界检查断连、显式取消或已写入终态的竞争条件。
   *
   * @param active 活动生成状态
   */
  private void ensureNotCancelled(ActiveGeneration active) {
    active.ensureWritable();
  }

  /** 把回答阶段的中立流事件映射为会话生成尝试、正文检查点和 SSE 事件。 */
  private final class ConversationAnswerObserver implements AnswerStage.Observer {
    private final ActiveGeneration active;

    /**
     * 创建指定活动回答的事件适配器。
     *
     * @param active 活动生成状态
     */
    private ConversationAnswerObserver(ActiveGeneration active) {
      this.active = active;
    }

    /** {@inheritDoc} */
    @Override
    public void normalizedAnswer(String content) {
      ensureNotCancelled(active);
      // 一次性替换已流出的正文，并同步检查点，保证断线读取与最终消息内容一致。
      active.replaceContent(content);
      ActiveGeneration.Snapshot snapshot = active.snapshot();
      messageMapper.checkpoint(active.ownerId(), active.generationId(), content);
      active.checkpointSaved(snapshot);
      active.channel().send(Kind.RESET, ConversationStreamEvents.reset("CITATION_NORMALIZED"));
      if (!snapshot.reasoning().isEmpty()) {
        active
            .channel()
            .send(Kind.REASONING_DELTA, ConversationStreamEvents.text(snapshot.reasoning()));
      }
      active.channel().send(Kind.DELTA, ConversationStreamEvents.text(content));
    }

    /** {@inheritDoc} */
    @Override
    public void started(AnswerGenerator.ModelTarget target, AnswerGenerator.AttemptReason reason) {
      ensureNotCancelled(active);
      UUID attemptId = UUID.randomUUID();
      int index = active.beginAttempt(attemptId);
      // 消息状态只在首个候选开始时迁移一次；provider fallback 和引用修复沿用 STREAMING。
      if (index == 1 && messageMapper.markStreaming(active.ownerId(), active.generationId()) == 0) {
        throw ApiException.cancelled();
      }
      GenerationAttempt attempt = new GenerationAttempt();
      attempt.setId(attemptId);
      attempt.setAssistantMessageId(active.generationId());
      attempt.setAttemptIndex(index);
      attempt.setReason(reason.name());
      attempt.setModelId(target.id());
      attempt.setProvider(target.provider());
      attempt.setModel(target.model());
      attempt.setStatus(GenerationAttemptStatus.STREAMING);
      attempt.setContent("");
      attempt.setReasoningContent("");
      generationAttemptMapper.insert(attempt);
      messageMapper.setModelInfo(
          active.ownerId(),
          active.generationId(),
          json.writeValueAsString(
              new ModelInfoResponse(target.id(), target.provider(), target.model())));
    }

    /** {@inheritDoc} */
    @Override
    public void delta(String text) {
      ensureNotCancelled(active);
      active.appendContent(text);
      active.channel().send(Kind.DELTA, ConversationStreamEvents.text(text));
      active.answerTrace().sent(false, text);
      checkpoint(active);
    }

    @Override
    public void reasoningDelta(String text) {
      ensureNotCancelled(active);
      active.appendReasoning(text);
      active.channel().send(Kind.REASONING_DELTA, ConversationStreamEvents.text(text));
      active.answerTrace().sent(true, text);
      checkpoint(active);
    }

    /** {@inheritDoc} */
    @Override
    public void completed(AnswerGenerator.ModelTarget target, String content, String finishReason) {
      generationAttemptMapper.complete(
          active.ownerId(), active.currentAttemptId(), content, finishReason);
      generationAttemptMapper.saveReasoning(
          active.ownerId(), active.currentAttemptId(), active.snapshot().reasoning());
    }

    /** {@inheritDoc} */
    @Override
    public void failed(
        AnswerGenerator.ModelTarget target, String partialContent, ApiException error) {
      if (active.currentAttemptId() != null) {
        generationAttemptMapper.fail(
            active.ownerId(),
            active.currentAttemptId(),
            active.cancelled() ? GenerationAttemptStatus.CANCELLED : GenerationAttemptStatus.FAILED,
            partialContent,
            error.code(),
            error.getMessage());
        generationAttemptMapper.saveReasoning(
            active.ownerId(), active.currentAttemptId(), active.snapshot().reasoning());
      }
    }

    /** {@inheritDoc} */
    @Override
    public void invalidReferences(String reasonCode, boolean repairScheduled) {
      if (active.currentAttemptId() != null) {
        // 模型流已经正常结束，引用校验发生在其后，因此需要显式作废 COMPLETED 尝试。
        generationAttemptMapper.invalidateCompleted(
            active.ownerId(), active.currentAttemptId(), reasonCode, "模型返回了非法引用");
      }
      if (!repairScheduled) {
        return;
      }
      // reset 之前同步清空数据库和检查点游标，避免修复流继续沿用首次正文的长度基线。
      active.resetContent();
      ActiveGeneration.Snapshot snapshot = active.snapshot();
      messageMapper.checkpoint(active.ownerId(), active.generationId(), "");
      messageMapper.checkpointReasoning(active.ownerId(), active.generationId(), "");
      active.checkpointSaved(snapshot);
      active.channel().send(Kind.RESET, ConversationStreamEvents.reset(reasonCode));
    }
  }

  /**
   * 按字符增量或时间间隔持久化流式回答检查点。
   *
   * @param active 活动生成状态
   */
  private void checkpoint(ActiveGeneration active) {
    ActiveGeneration.Snapshot snapshot =
        active.checkpointDue(config.getCheckpointChars(), config.getCheckpointIntervalMs());
    if (snapshot == null) {
      return;
    }
    messageMapper.checkpoint(active.ownerId(), active.generationId(), snapshot.content());
    messageMapper.checkpointReasoning(
        active.ownerId(), active.generationId(), snapshot.reasoning());
    if (snapshot.attemptId() != null) {
      generationAttemptMapper.checkpoint(
          active.ownerId(), snapshot.attemptId(), snapshot.content());
      generationAttemptMapper.checkpointReasoning(
          active.ownerId(), snapshot.attemptId(), snapshot.reasoning());
    }
    active.checkpointSaved(snapshot);
  }
}
