package com.hnu.backend.conversation.service;

import com.hnu.backend.configuration.ConversationProperties;
import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.entity.GenerationAttempt;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.GenerationAttemptMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.conversation.vo.ConversationResponses;
import com.hnu.backend.rag.answer.AnswerGenerator;
import com.hnu.backend.rag.answer.AnswerResult;
import com.hnu.backend.rag.answer.AnswerStage;
import com.hnu.backend.rag.deduplication.DeduplicationStage;
import com.hnu.backend.rag.execution.ExecutionStage;
import com.hnu.backend.rag.prompt.AssembledPrompt;
import com.hnu.backend.rag.prompt.PromptAssemblyStage;
import com.hnu.backend.rag.rerank.RerankStage;
import com.hnu.backend.rag.vo.ModelInfoResponse;
import com.hnu.backend.rag.vo.SourceResponse;
import com.hnu.backend.shared.error.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/** 管理会话生命周期，并把 RAG 流水线输出映射为持久化消息和 SSE 事件。 */
@Service
public class ConversationService {
  private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
  private final ConversationMapper conversations;
  private final MessageMapper messages;
  private final GenerationAttemptMapper attempts;
  private final ConversationContextService conversationContext;
  private final ExecutionStage executionStage;
  private final DeduplicationStage deduplicationStage;
  private final RerankStage rerankStage;
  private final PromptAssemblyStage prompts;
  private final AnswerStage answers;
  private final RagProperties rag;
  private final ConversationProperties config;
  private final TransactionTemplate tx;
  private final JsonMapper json = JsonMapper.builder().build();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ConcurrentMap<UUID, ActiveGeneration> activeByConversation =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, ActiveGeneration> activeByGeneration =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, Object> conversationLocks = new ConcurrentHashMap<>();

  /**
   * 创建会话应用服务。
   *
   * @param conversations 会话持久化接口
   * @param messages 消息持久化接口
   * @param attempts 模型生成尝试持久化接口
   * @param conversationContext 会话记忆、规划和路由准备服务
   * @param executionStage 子问题执行阶段
   * @param deduplicationStage 证据去重阶段
   * @param rerankStage 证据重排阶段
   * @param prompts 最终提示词组装阶段
   * @param answers 最终回答、引用校验和修复阶段
   * @param rag RAG 输入配置
   * @param config 会话检查点配置
   * @param tx 终态持久化事务模板
   */
  public ConversationService(
      ConversationMapper conversations,
      MessageMapper messages,
      GenerationAttemptMapper attempts,
      ConversationContextService conversationContext,
      ExecutionStage executionStage,
      DeduplicationStage deduplicationStage,
      RerankStage rerankStage,
      PromptAssemblyStage prompts,
      AnswerStage answers,
      RagProperties rag,
      ConversationProperties config,
      TransactionTemplate tx) {
    this.conversations = conversations;
    this.messages = messages;
    this.attempts = attempts;
    this.conversationContext = conversationContext;
    this.executionStage = executionStage;
    this.deduplicationStage = deduplicationStage;
    this.rerankStage = rerankStage;
    this.prompts = prompts;
    this.answers = answers;
    this.rag = rag;
    this.config = config;
    this.tx = tx;
  }

  /** 将进程异常退出时遗留的运行中消息和尝试恢复为终态。 */
  @PostConstruct
  void recoverInterrupted() {
    int recoveredAttempts = attempts.recoverInterrupted();
    int recoveredMessages = messages.recoverInterrupted();
    if (recoveredMessages > 0 || recoveredAttempts > 0) {
      log.warn(
          "Recovered interrupted generation state messages={} attempts={}",
          recoveredMessages,
          recoveredAttempts);
    }
  }

  /** 取消全部内存生成任务并关闭虚拟线程执行器。 */
  @PreDestroy
  void close() {
    activeByConversation.values().forEach(ActiveGeneration::cancel);
    executor.shutdownNow();
  }

  /**
   * 创建会话。
   *
   * @param ownerId 所属用户标识；新会话只对该用户可见
   * @param rawTitle 未归一化标题
   * @return 新会话摘要
   */
  public ConversationResponses.Summary create(UUID ownerId, String rawTitle) {
    String title = normalizeTitle(rawTitle);
    UUID id = UUID.randomUUID();
    conversations.insert(ownerId, id, title);
    return summary(require(ownerId, id));
  }

  /**
   * 按标题查询会话列表。
   *
   * @param ownerId 所属用户标识
   * @param rawQuery 未转义的标题查询文本
   * @param rawLimit 调用方请求的数量上限
   * @return 按持久化层规则排序的会话摘要
   */
  public List<ConversationResponses.Summary> list(UUID ownerId, String rawQuery, int rawLimit) {
    String query =
        rawQuery == null
            ? ""
            : rawQuery.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    int limit = rawLimit <= 0 ? 50 : Math.min(rawLimit, 100);
    return conversations.list(ownerId, query, limit).stream().map(this::summary).toList();
  }

  /**
   * 加载会话详情及每轮全部回答版本。
   *
   * @param ownerId 所属用户标识
   * @param id 会话 ID
   * @return 可直接返回给前端的会话详情
   */
  public ConversationResponses.Detail get(UUID ownerId, UUID id) {
    Conversation conversation = require(ownerId, id);
    List<Message> all = messages.list(ownerId, id);
    Map<Integer, Message> users = new LinkedHashMap<>();
    Map<Integer, List<Message>> assistants = new LinkedHashMap<>();
    for (Message message : all) {
      if ("USER".equals(message.getRole())) users.put(message.getTurnIndex(), message);
      else
        assistants
            .computeIfAbsent(message.getTurnIndex(), ignored -> new ArrayList<>())
            .add(message);
    }
    List<ConversationResponses.Turn> turns = new ArrayList<>();
    for (Message user : users.values()) {
      List<ConversationResponses.AssistantMessage> versions =
          assistants.getOrDefault(user.getTurnIndex(), List.of()).stream()
              .map(this::assistantResponse)
              .toList();
      UUID active =
          versions.stream()
              .filter(ConversationResponses.AssistantMessage::active)
              .map(ConversationResponses.AssistantMessage::id)
              .findFirst()
              .orElse(null);
      turns.add(
          new ConversationResponses.Turn(
              new ConversationResponses.UserMessage(
                  user.getId(), user.getTurnIndex(), user.getContent(), user.getCreatedAt()),
              versions,
              active));
    }
    return new ConversationResponses.Detail(
        conversation.getId(),
        conversation.getTitle(),
        conversation.getCreatedAt(),
        conversation.getUpdatedAt(),
        List.copyOf(turns));
  }

  /**
   * 修改会话标题。
   *
   * @param ownerId 所属用户标识
   * @param id 会话 ID
   * @param rawTitle 未归一化标题
   * @return 更新后的会话摘要
   */
  public ConversationResponses.Summary rename(UUID ownerId, UUID id, String rawTitle) {
    require(ownerId, id);
    conversations.rename(ownerId, id, normalizeTitle(rawTitle));
    return summary(require(ownerId, id));
  }

  /**
   * 删除没有运行中生成任务的会话。
   *
   * @param ownerId 所属用户标识
   * @param id 会话 ID
   */
  public void delete(UUID ownerId, UUID id) {
    require(ownerId, id);
    if (activeByConversation.containsKey(id) || messages.countRunning(ownerId, id) > 0) {
      throw ApiException.conflict("GENERATION_IN_PROGRESS", "请先停止当前生成再删除会话");
    }
    conversations.delete(ownerId, id);
    conversationLocks.remove(id);
  }

  /**
   * 新建用户消息和待生成回答，并立即返回 SSE 输出通道。
   *
   * @param ownerId 所属用户标识；异步任务会显式捕获该值
   * @param conversationId 会话 ID
   * @param clientMessageId 客户端幂等消息 ID
   * @param rawQuestion 未归一化用户问题
   * @param requestId HTTP 请求追踪 ID
   * @return 当前生成或历史幂等结果的 SSE 通道
   */
  public SseEmitter ask(
      UUID ownerId,
      UUID conversationId,
      UUID clientMessageId,
      String rawQuestion,
      String requestId) {
    String question = normalizeQuestion(rawQuestion);
    Object lock = conversationLocks.computeIfAbsent(conversationId, ignored -> new Object());
    synchronized (lock) {
      Conversation conversation = require(ownerId, conversationId);
      Message existing = messages.findByClientRequest(ownerId, conversationId, clientMessageId);
      if (existing != null) {
        Message assistant =
            "USER".equals(existing.getRole())
                ? messages.latestReply(ownerId, existing.getId())
                : existing;
        return replayOrConflict(conversation, existing, assistant, requestId);
      }
      ensureIdle(ownerId, conversationId);
      PreparedMessages prepared =
          tx.execute(
              ignored -> {
                int turn = messages.nextTurn(ownerId, conversationId);
                Message user = userMessage(conversationId, clientMessageId, turn, question);
                messages.insert(user);
                Message assistant = assistantMessage(conversationId, null, turn, 1, user.getId());
                messages.insert(assistant);
                conversations.touch(ownerId, conversationId);
                return new PreparedMessages(user, assistant);
              });
      return launch(conversation, prepared.user(), prepared.assistant(), requestId);
    }
  }

  /**
   * 重试失败或已取消的回答。
   *
   * @param conversationId 会话 ID
   * @param assistantMessageId 待重试回答 ID
   * @param clientRequestId 本次重试的幂等 ID
   * @param requestId HTTP 请求追踪 ID
   * @return 新回答版本的 SSE 通道
   */
  public SseEmitter retry(
      UUID ownerId,
      UUID conversationId,
      UUID assistantMessageId,
      UUID clientRequestId,
      String requestId) {
    return restart(ownerId, conversationId, assistantMessageId, clientRequestId, requestId, false);
  }

  /**
   * 为最后一轮当前成功回答生成新版本。
   *
   * @param conversationId 会话 ID
   * @param assistantMessageId 待重新生成回答 ID
   * @param clientRequestId 本次重新生成的幂等 ID
   * @param requestId HTTP 请求追踪 ID
   * @return 新回答版本的 SSE 通道
   */
  public SseEmitter regenerate(
      UUID ownerId,
      UUID conversationId,
      UUID assistantMessageId,
      UUID clientRequestId,
      String requestId) {
    return restart(ownerId, conversationId, assistantMessageId, clientRequestId, requestId, true);
  }

  /**
   * 校验重试或重新生成条件，创建新的回答版本并启动生成。
   *
   * @param conversationId 会话 ID
   * @param assistantMessageId 原回答 ID
   * @param clientRequestId 新回答版本的幂等 ID
   * @param requestId HTTP 请求追踪 ID
   * @param regenerate true 表示重新生成成功回答，false 表示重试失败回答
   * @return 新回答版本的 SSE 通道
   */
  private SseEmitter restart(
      UUID ownerId,
      UUID conversationId,
      UUID assistantMessageId,
      UUID clientRequestId,
      String requestId,
      boolean regenerate) {
    Object lock = conversationLocks.computeIfAbsent(conversationId, ignored -> new Object());
    synchronized (lock) {
      Conversation conversation = require(ownerId, conversationId);
      Message duplicate = messages.findByClientRequest(ownerId, conversationId, clientRequestId);
      if (duplicate != null) {
        Message user = messages.find(ownerId, duplicate.getReplyToId());
        return replayOrConflict(conversation, user, duplicate, requestId);
      }
      ensureIdle(ownerId, conversationId);
      Message previous = messages.find(ownerId, assistantMessageId);
      if (previous == null
          || !conversationId.equals(previous.getConversationId())
          || !"ASSISTANT".equals(previous.getRole())) {
        throw ApiException.notFound("MESSAGE_NOT_FOUND", "回答不存在");
      }
      if (regenerate) {
        int lastTurn = messages.nextTurn(ownerId, conversationId) - 1;
        if (!previous.isActive()
            || !"COMPLETED".equals(previous.getStatus())
            || previous.getTurnIndex() != lastTurn) {
          throw ApiException.conflict("REGENERATE_NOT_ALLOWED", "只能重新生成会话最后一轮的当前成功回答");
        }
      } else if (!("FAILED".equals(previous.getStatus())
          || "CANCELLED".equals(previous.getStatus()))) {
        throw ApiException.conflict("RETRY_NOT_ALLOWED", "只能重试失败或已停止的回答");
      }
      Message user = messages.find(ownerId, previous.getReplyToId());
      Message next =
          tx.execute(
              ignored -> {
                messages.deactivateReplies(ownerId, user.getId());
                Message value =
                    assistantMessage(
                        conversationId,
                        clientRequestId,
                        user.getTurnIndex(),
                        messages.nextVariant(ownerId, user.getId()),
                        user.getId());
                messages.insert(value);
                conversations.touch(ownerId, conversationId);
                return value;
              });
      return launch(conversation, user, next, requestId);
    }
  }

  /**
   * 取消内存中或仅存在于数据库中的运行中回答。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话 ID
   * @param generationId 回答生成 ID
   */
  public void cancel(UUID ownerId, UUID conversationId, UUID generationId) {
    require(ownerId, conversationId);
    ActiveGeneration active = activeByGeneration.get(generationId);
    if (active != null
        && active.ownerId.equals(ownerId)
        && active.conversation().getId().equals(conversationId)) {
      active.cancel();
      cancelTerminal(active);
      return;
    }

    Message stored = messages.find(ownerId, generationId);
    if (stored == null
        || !conversationId.equals(stored.getConversationId())
        || !"ASSISTANT".equals(stored.getRole())) {
      throw ApiException.notFound("GENERATION_NOT_FOUND", "生成任务不存在");
    }
    if (!("PENDING".equals(stored.getStatus()) || "STREAMING".equals(stored.getStatus()))) return;
    tx.executeWithoutResult(
        ignored -> {
          int changed =
              messages.cancelRunning(ownerId, generationId, conversationId, stored.getContent());
          attempts.cancelRunning(ownerId, generationId);
          if (changed > 0) conversations.touch(ownerId, conversationId);
        });
  }

  /**
   * 取消指定用户当前仍在运行的全部回答。
   *
   * <p>账号禁用或密码重置会调用此方法，防止已建立的 SSE 连接在会话撤销后继续输出。
   *
   * @param ownerId 用户标识
   */
  public void cancelByOwner(UUID ownerId) {
    activeByGeneration.values().stream()
        .filter(active -> active.ownerId.equals(ownerId))
        .toList()
        .forEach(
            active -> {
              active.cancel();
              cancelTerminal(active);
            });
  }

  /**
   * 确认会话当前没有运行中的回答，避免并发轮次破坏顺序。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话 ID
   */
  private void ensureIdle(UUID ownerId, UUID conversationId) {
    if (activeByConversation.containsKey(conversationId)
        || messages.countRunning(ownerId, conversationId) > 0) {
      throw ApiException.conflict("GENERATION_IN_PROGRESS", "该会话正在生成回答");
    }
  }

  /**
   * 对已存在的幂等请求重放终态，运行中请求则返回冲突。
   *
   * @param conversation 会话
   * @param user 原用户消息
   * @param assistant 已存在的回答
   * @param requestId 当前 HTTP 请求追踪 ID
   * @return 仅发送已持久化状态的 SSE 通道
   */
  private SseEmitter replayOrConflict(
      Conversation conversation, Message user, Message assistant, String requestId) {
    if (assistant == null) throw ApiException.conflict("MESSAGE_INCOMPLETE", "消息尚未创建回答");
    if ("PENDING".equals(assistant.getStatus()) || "STREAMING".equals(assistant.getStatus())) {
      throw ApiException.conflict("GENERATION_IN_PROGRESS", "相同请求正在生成");
    }
    SseEmitter emitter = new SseEmitter(0L);
    executor.submit(
        () -> {
          try {
            send(emitter, "started", startedPayload(conversation, user, assistant));
            String event =
                switch (assistant.getStatus()) {
                  case "COMPLETED" -> "complete";
                  case "CANCELLED" -> "cancelled";
                  default -> "error";
                };
            send(emitter, event, terminalPayload(assistant, requestId));
            emitter.complete();
          } catch (RuntimeException ignored) {
            emitter.completeWithError(ignored);
          }
        });
    return emitter;
  }

  /**
   * 注册活动生成状态并异步启动完整回答流程。
   *
   * @param conversation 会话
   * @param user 用户消息
   * @param assistant 待生成回答
   * @param requestId HTTP 请求追踪 ID
   * @return 实时 SSE 通道
   */
  private SseEmitter launch(
      Conversation conversation, Message user, Message assistant, String requestId) {
    SseEmitter emitter = new SseEmitter(0L);
    ActiveGeneration active =
        new ActiveGeneration(
            conversation, user, assistant, requestId, emitter, answers.newControl());
    activeByConversation.put(conversation.getId(), active);
    activeByGeneration.put(assistant.getId(), active);
    emitter.onCompletion(() -> disconnect(active));
    emitter.onTimeout(() -> disconnect(active));
    emitter.onError(ignored -> disconnect(active));
    active.future = executor.submit(() -> generate(active));
    return emitter;
  }

  /**
   * 处理客户端断连，把未结束的生成转换为取消终态。
   *
   * @param active 活动生成状态
   */
  private void disconnect(ActiveGeneration active) {
    if (active.terminal.get()) return;
    active.cancel();
    cancelTerminal(active);
  }

  /**
   * 执行记忆、规划、路由、检索、去重、重排、提示词组装和流式回答全链路。
   *
   * @param active 活动生成状态
   */
  private void generate(ActiveGeneration active) {
    active.running.set(true);
    try {
      if (active.control.cancelled()) throw ApiException.cancelled();
      send(
          active.emitter,
          "started",
          startedPayload(active.conversation, active.user, active.assistant));
      var prepared =
          conversationContext.prepare(
              active.conversation, active.user.getTurnIndex(), active.user.getContent());
      ensureNotCancelled(active);
      String standaloneQuestion = prepared.queryPlan().standaloneQuestion();
      if (prepared.routingPlan().systemChatOnly()) {
        answerSystemChat(active, prepared);
        return;
      }
      var execution =
          executionStage.execute(
              active.ownerId,
              prepared.queryPlan(),
              prepared.routingPlan(),
              null,
              active.control::cancelled);
      ensureNotCancelled(active);
      var deduplicated = deduplicationStage.execute(execution.candidates(), execution.budget());
      ensureNotCancelled(active);
      var reranked =
          rerankStage.execute(
              prepared.queryPlan(),
              execution,
              deduplicated.candidates(),
              active.control::cancelled);
      ensureNotCancelled(active);
      AssembledPrompt prompt =
          prompts.assemblePipeline(
              prepared.memory(),
              active.user.getContent(),
              prepared.queryPlan(),
              prepared.routingPlan(),
              execution,
              reranked.selectedCandidates());
      messages.prepare(
          active.ownerId,
          active.assistant.getId(),
          standaloneQuestion,
          json.writeValueAsString(prompt.sources()));
      ensureNotCancelled(active);
      active.assistant.setRetrievalQuery(standaloneQuestion);
      active.assistant.setSourcesJson(json.writeValueAsString(prompt.sources()));
      AnswerResult answer =
          answers.execute(prompt, new ConversationAnswerObserver(active), active.control);
      complete(active, answer.content(), answer.citations(), answer.generation());
    } catch (ApiException e) {
      if (active.control.cancelled() || "GENERATION_CANCELLED".equals(e.code()))
        cancelTerminal(active);
      else errorTerminal(active, e.code(), e.getMessage());
    } catch (RuntimeException e) {
      if (active.control.cancelled()) {
        cancelTerminal(active);
        return;
      }
      log.error(
          "conversation={} generation={} exceptionType={}",
          active.conversation.getId(),
          active.assistant.getId(),
          e.getClass().getSimpleName(),
          e);
      errorTerminal(active, "INTERNAL_ERROR", "服务暂时不可用，请稍后重试");
    }
  }

  /**
   * 为纯系统闲聊路由组装无证据提示词并流式回答。
   *
   * @param active 活动生成状态
   * @param prepared 已准备的记忆、问题计划和路由
   */
  private void answerSystemChat(
      ActiveGeneration active, ConversationContextService.PreparedContext prepared) {
    messages.prepare(active.ownerId, active.assistant.getId(), null, "[]");
    ensureNotCancelled(active);
    active.assistant.setRetrievalQuery(null);
    active.assistant.setSourcesJson("[]");
    AssembledPrompt prompt =
        prompts.assembleSystemChat(
            prepared.memory(),
            active.user.getContent(),
            prepared.queryPlan(),
            prepared.routingPlan());
    AnswerResult answer =
        answers.execute(prompt, new ConversationAnswerObserver(active), active.control);
    complete(active, answer.content(), answer.citations(), answer.generation());
  }

  /**
   * 在各阶段边界检查断连、显式取消或已写入终态的竞争条件。
   *
   * @param active 活动生成状态
   */
  private void ensureNotCancelled(ActiveGeneration active) {
    if (active.control.cancelled() || active.terminal.get()) throw ApiException.cancelled();
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
    public void started(AnswerGenerator.ModelTarget target, AnswerGenerator.AttemptReason reason) {
      ensureNotCancelled(active);
      int index = active.attemptCounter.incrementAndGet();
      // 消息状态只在首个候选开始时迁移一次；provider fallback 和引用修复沿用 STREAMING。
      if (index == 1 && messages.markStreaming(active.ownerId, active.assistant.getId()) == 0) {
        throw ApiException.cancelled();
      }
      GenerationAttempt attempt = new GenerationAttempt();
      attempt.setId(UUID.randomUUID());
      attempt.setAssistantMessageId(active.assistant.getId());
      attempt.setAttemptIndex(index);
      attempt.setReason(reason.name());
      attempt.setModelId(target.id());
      attempt.setProvider(target.provider());
      attempt.setModel(target.model());
      attempt.setStatus("STREAMING");
      attempt.setContent("");
      attempts.insert(attempt);
      active.currentAttemptId = attempt.getId();
      messages.setModelInfo(
          active.ownerId,
          active.assistant.getId(),
          json.writeValueAsString(
              new ModelInfoResponse(target.id(), target.provider(), target.model())));
    }

    /** {@inheritDoc} */
    @Override
    public void delta(String text) {
      ensureNotCancelled(active);
      active.buffer.append(text);
      send(active.emitter, "delta", event("text", text));
      checkpoint(active);
    }

    /** {@inheritDoc} */
    @Override
    public void completed(AnswerGenerator.ModelTarget target, String content, String finishReason) {
      attempts.complete(active.ownerId, active.currentAttemptId, content, finishReason);
    }

    /** {@inheritDoc} */
    @Override
    public void failed(
        AnswerGenerator.ModelTarget target, String partialContent, ApiException error) {
      if (active.currentAttemptId != null) {
        attempts.fail(
            active.ownerId,
            active.currentAttemptId,
            active.control.cancelled() ? "CANCELLED" : "FAILED",
            partialContent,
            error.code(),
            error.getMessage());
      }
    }

    /** {@inheritDoc} */
    @Override
    public void invalidReferences(String reasonCode, boolean repairScheduled) {
      if (active.currentAttemptId != null) {
        // 模型流已经正常结束，引用校验发生在其后，因此需要显式作废 COMPLETED 尝试。
        attempts.invalidateCompleted(
            active.ownerId, active.currentAttemptId, reasonCode, "模型返回了非法引用");
      }
      if (!repairScheduled) return;
      // reset 之前同步清空数据库和检查点游标，避免修复流继续沿用首次正文的长度基线。
      active.buffer.setLength(0);
      messages.checkpoint(active.ownerId, active.assistant.getId(), "");
      active.lastCheckpointLength = 0;
      active.lastCheckpointAt = System.currentTimeMillis();
      send(active.emitter, "reset", event("reason", reasonCode));
    }
  }

  /**
   * 按字符增量或时间间隔持久化流式回答检查点。
   *
   * @param active 活动生成状态
   */
  private void checkpoint(ActiveGeneration active) {
    long now = System.currentTimeMillis();
    if (active.buffer.length() - active.lastCheckpointLength >= config.getCheckpointChars()
        || now - active.lastCheckpointAt >= config.getCheckpointIntervalMs()) {
      messages.checkpoint(active.ownerId, active.assistant.getId(), active.buffer.toString());
      if (active.currentAttemptId != null)
        attempts.checkpoint(active.ownerId, active.currentAttemptId, active.buffer.toString());
      active.lastCheckpointLength = active.buffer.length();
      active.lastCheckpointAt = now;
    }
  }

  /**
   * 将当前模型尝试记录为指定失败终态。
   *
   * @param active 活动生成状态
   * @param status 尝试终态
   * @param code 稳定错误码
   * @param message 用户可读错误信息
   */
  private void failCurrentAttempt(
      ActiveGeneration active, String status, String code, String message) {
    if (active.currentAttemptId != null)
      attempts.fail(
          active.ownerId, active.currentAttemptId, status, active.buffer.toString(), code, message);
  }

  /**
   * 原子持久化成功回答，并以数据库中的最终快照发送完成事件。
   *
   * @param active 活动生成状态
   * @param content 完整回答正文
   * @param citations 通过白名单校验的知识引用
   * @param generation 回答阶段返回的实际模型元数据；固定兜底回答时为空
   */
  private void complete(
      ActiveGeneration active,
      String content,
      List<String> citations,
      AnswerGenerator.Generation generation) {
    if (active.control.cancelled()) {
      cancelTerminal(active);
      return;
    }
    if (!active.terminal.compareAndSet(false, true)) return;
    try {
      String modelInfo =
          generation == null
              ? null
              : json.writeValueAsString(
                  new ModelInfoResponse(
                      generation.modelId(), generation.provider(), generation.model()));
      tx.executeWithoutResult(
          ignored -> {
            int changed =
                messages.complete(
                    active.ownerId,
                    active.generationId,
                    content,
                    json.writeValueAsString(citations),
                    modelInfo);
            if (changed > 0) conversations.touch(active.ownerId, active.conversation.getId());
          });
      finishPersisted(active, "complete", "INTERNAL_ERROR", "回答保存失败，请重试");
    } catch (RuntimeException e) {
      terminalPersistenceFailed(active, "complete", e);
      finish(active, "error", terminalEvent("INTERNAL_ERROR", "回答保存失败，请重试", active.requestId));
    }
  }

  /**
   * 原子持久化取消终态并发送取消事件。
   *
   * @param active 活动生成状态
   */
  private void cancelTerminal(ActiveGeneration active) {
    if (!active.terminal.compareAndSet(false, true)) return;
    String content = active.buffer.toString();
    try {
      tx.executeWithoutResult(
          ignored -> {
            int changed =
                messages.cancelRunning(
                    active.ownerId, active.generationId, active.conversation.getId(), content);
            attempts.cancelRunning(active.ownerId, active.generationId);
            if (changed > 0) conversations.touch(active.ownerId, active.conversation.getId());
          });
      finishPersisted(active, "cancelled", "GENERATION_CANCELLED", "生成已停止");
    } catch (RuntimeException e) {
      terminalPersistenceFailed(active, "cancelled", e);
      finish(active, "cancelled", terminalEvent("GENERATION_CANCELLED", "生成已停止", active.requestId));
    }
  }

  /**
   * 原子持久化失败终态并发送错误事件。
   *
   * @param active 活动生成状态
   * @param code 稳定错误码
   * @param message 用户可读错误信息
   */
  private void errorTerminal(ActiveGeneration active, String code, String message) {
    if (!active.terminal.compareAndSet(false, true)) return;
    String content = active.buffer.toString();
    try {
      tx.executeWithoutResult(
          ignored -> {
            int changed =
                messages.terminalFailure(
                    active.ownerId, active.generationId, "FAILED", content, code, message);
            if (changed > 0) {
              failCurrentAttempt(active, "FAILED", code, message);
              conversations.touch(active.ownerId, active.conversation.getId());
            }
          });
      finishPersisted(active, "error", code, message);
    } catch (RuntimeException e) {
      terminalPersistenceFailed(active, "error", e);
      finish(active, "error", terminalEvent(code, message, active.requestId));
    }
  }

  /**
   * 清理活动任务索引并关闭 SSE 通道。
   *
   * @param active 活动生成状态
   * @param event 终态事件名
   * @param payload 终态事件数据
   */
  private void finish(ActiveGeneration active, String event, Object payload) {
    activeByConversation.remove(active.conversation.getId(), active);
    activeByGeneration.remove(active.generationId, active);
    try {
      send(active.emitter, event, payload);
      active.emitter.complete();
    } catch (RuntimeException e) {
      active.emitter.completeWithError(e);
    }
  }

  /**
   * 重新读取数据库终态，保证 SSE 展示内容与最终持久化状态一致。
   *
   * @param active 活动生成状态
   * @param fallbackEvent 找不到持久化消息时的事件名
   * @param fallbackCode 找不到持久化消息时的错误码
   * @param fallbackMessage 找不到持久化消息时的提示
   */
  private void finishPersisted(
      ActiveGeneration active, String fallbackEvent, String fallbackCode, String fallbackMessage) {
    Message stored = messages.find(active.ownerId, active.generationId);
    if (stored == null) {
      finish(active, fallbackEvent, terminalEvent(fallbackCode, fallbackMessage, active.requestId));
      return;
    }
    active.assistant = stored;
    String event =
        switch (stored.getStatus()) {
          case "COMPLETED" -> "complete";
          case "CANCELLED" -> "cancelled";
          default -> "error";
        };
    finish(active, event, terminalPayload(stored, active.requestId));
  }

  /**
   * 记录终态持久化失败，不输出会话正文或模型内容。
   *
   * @param active 活动生成状态
   * @param terminalEvent 原计划写入的终态事件
   * @param error 持久化异常
   */
  private void terminalPersistenceFailed(
      ActiveGeneration active, String terminalEvent, RuntimeException error) {
    log.error(
        "conversation={} generation={} terminalEvent={} persistenceFailed",
        active.conversation.getId(),
        active.generationId,
        terminalEvent,
        error);
  }

  /**
   * 发送单个 SSE 事件；断连和重复关闭统一转换为取消异常。
   *
   * @param emitter SSE 通道
   * @param name 事件名
   * @param data 事件数据
   */
  private void send(SseEmitter emitter, String name, Object data) {
    try {
      emitter.send(SseEmitter.event().name(name).data(data));
    } catch (IOException | IllegalStateException e) {
      throw ApiException.cancelled();
    }
  }

  /**
   * 构造 started 事件数据。
   *
   * @param conversation 会话
   * @param user 用户消息
   * @param assistant 回答消息
   * @return 带协议版本和前端关联 ID 的有序对象
   */
  private Map<String, Object> startedPayload(
      Conversation conversation, Message user, Message assistant) {
    Map<String, Object> value = event("conversationId", conversation.getId());
    value.put("userMessageId", user.getId());
    value.put("assistantMessageId", assistant.getId());
    value.put("generationId", assistant.getId());
    value.put("turnIndex", user.getTurnIndex());
    value.put("variantIndex", assistant.getVariantIndex());
    return value;
  }

  /**
   * 构造包含完整回答快照的终态事件数据。
   *
   * @param assistant 已持久化回答
   * @param requestId HTTP 请求追踪 ID
   * @return 前端兼容终态数据
   */
  private Map<String, Object> terminalPayload(Message assistant, String requestId) {
    Map<String, Object> value = event("assistantMessage", assistantResponse(assistant));
    value.put("requestId", requestId);
    value.put("retryable", !"INVALID_QUESTION".equals(assistant.getErrorCode()));
    if (assistant.getErrorCode() != null) value.put("code", assistant.getErrorCode());
    if (assistant.getErrorMessage() != null) value.put("message", assistant.getErrorMessage());
    return value;
  }

  /**
   * 构造没有可读取回答快照时使用的终态错误数据。
   *
   * @param code 稳定错误码
   * @param message 用户可读错误信息
   * @param requestId HTTP 请求追踪 ID
   * @return 前端兼容终态数据
   */
  private Map<String, Object> terminalEvent(String code, String message, String requestId) {
    Map<String, Object> value = event("code", code);
    value.put("message", message);
    value.put("requestId", requestId);
    value.put("retryable", !"INVALID_QUESTION".equals(code));
    return value;
  }

  /**
   * 创建带固定协议版本的有序事件对象。
   *
   * @param key 首个业务字段名
   * @param value 首个业务字段值
   * @return 可以继续追加字段的事件对象
   */
  private Map<String, Object> event(String key, Object value) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("schemaVersion", 1);
    event.put(key, value);
    return event;
  }

  /**
   * 将消息实体中的 JSON 快照转换为前端回答结构。
   *
   * @param message 回答消息实体
   * @return 前端回答版本
   */
  private ConversationResponses.AssistantMessage assistantResponse(Message message) {
    List<SourceResponse> sources = readArray(message.getSourcesJson(), SourceResponse[].class);
    List<String> citations = readArray(message.getCitationsJson(), String[].class);
    ModelInfoResponse modelInfo =
        message.getModelInfoJson() == null
            ? null
            : json.readValue(message.getModelInfoJson(), ModelInfoResponse.class);
    return new ConversationResponses.AssistantMessage(
        message.getId(),
        message.getReplyToId(),
        message.getTurnIndex(),
        message.getVariantIndex(),
        message.isActive(),
        message.getStatus(),
        message.getContent(),
        message.getRetrievalQuery(),
        sources,
        citations,
        modelInfo,
        message.getErrorCode(),
        message.getErrorMessage(),
        message.getCreatedAt(),
        message.getUpdatedAt(),
        message.getCompletedAt());
  }

  /**
   * 将可空 JSON 数组字段读取为不可变列表。
   *
   * @param encoded JSON 数组文本
   * @param type 数组运行时类型
   * @param <T> 数组元素类型
   * @return 空列表或反序列化后的不可变列表
   */
  private <T> List<T> readArray(String encoded, Class<T[]> type) {
    if (encoded == null || encoded.isBlank()) return List.of();
    return List.of(json.readValue(encoded, type));
  }

  /**
   * 加载会话，不存在时抛出统一业务异常。
   *
   * @param ownerId 所属用户标识；不匹配时与不存在统一处理
   * @param id 会话 ID
   * @return 持久化会话实体
   */
  private Conversation require(UUID ownerId, UUID id) {
    Conversation value = conversations.find(ownerId, id);
    if (value == null) throw ApiException.notFound("CONVERSATION_NOT_FOUND", "会话不存在");
    return value;
  }

  /**
   * 将会话实体转换为列表摘要。
   *
   * @param value 会话实体
   * @return 前端会话摘要
   */
  private ConversationResponses.Summary summary(Conversation value) {
    return new ConversationResponses.Summary(
        value.getId(), value.getTitle(), value.getCreatedAt(), value.getUpdatedAt());
  }

  /**
   * 去除标题首尾空白并校验长度和控制字符。
   *
   * @param raw 原始标题
   * @return 合法标题
   */
  private String normalizeTitle(String raw) {
    String value = raw == null ? "" : raw.strip();
    if (value.isEmpty() || value.length() > 200 || value.chars().anyMatch(Character::isISOControl))
      throw ApiException.bad("INVALID_CONVERSATION_TITLE", "会话标题应为 1 到 200 个有效字符");
    return value;
  }

  /**
   * 去除问题首尾空白并应用 RAG 最大长度校验。
   *
   * @param raw 原始问题
   * @return 合法用户问题
   */
  private String normalizeQuestion(String raw) {
    String value = raw == null ? "" : raw.strip();
    if (value.isEmpty() || value.length() > rag.getMaxQuestionChars())
      throw ApiException.bad(
          "INVALID_QUESTION", "请输入非空问题，长度不能超过 " + rag.getMaxQuestionChars() + " 字符");
    return value;
  }

  /**
   * 创建尚未写入数据库的用户消息实体。
   *
   * @param conversationId 会话 ID
   * @param clientId 客户端幂等 ID
   * @param turn 轮次编号
   * @param content 用户问题
   * @return 初始化完成的用户消息
   */
  private Message userMessage(UUID conversationId, UUID clientId, int turn, String content) {
    Message value = new Message();
    value.setId(UUID.randomUUID());
    value.setConversationId(conversationId);
    value.setClientRequestId(clientId);
    value.setRole("USER");
    value.setTurnIndex(turn);
    value.setVariantIndex(0);
    value.setActive(true);
    value.setStatus("COMPLETED");
    value.setContent(content);
    value.setSourcesJson("[]");
    value.setCitationsJson("[]");
    return value;
  }

  /**
   * 创建尚未写入数据库的待生成回答实体。
   *
   * @param conversationId 会话 ID
   * @param clientId 客户端幂等 ID
   * @param turn 轮次编号
   * @param variant 回答版本编号
   * @param replyTo 对应用户消息 ID
   * @return 初始化完成的回答消息
   */
  private Message assistantMessage(
      UUID conversationId, UUID clientId, int turn, int variant, UUID replyTo) {
    Message value = new Message();
    value.setId(UUID.randomUUID());
    value.setConversationId(conversationId);
    value.setClientRequestId(clientId);
    value.setRole("ASSISTANT");
    value.setTurnIndex(turn);
    value.setVariantIndex(variant);
    value.setActive(true);
    value.setReplyToId(replyTo);
    value.setStatus("PENDING");
    value.setContent("");
    value.setSourcesJson("[]");
    value.setCitationsJson("[]");
    return value;
  }

  /**
   * 同一事务中创建的一对用户消息和初始回答。
   *
   * @param user 已持久化用户消息
   * @param assistant 已持久化待生成回答
   */
  private record PreparedMessages(Message user, Message assistant) {}

  /** 跨异步回调维护一次流式生成的取消、缓冲、尝试和终态竞争状态。 */
  private static final class ActiveGeneration {
    private final UUID ownerId;
    private final Conversation conversation;
    private final Message user;
    private final UUID generationId;
    private Message assistant;
    private final String requestId;
    private final SseEmitter emitter;
    private final AnswerGenerator.Control control;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger attemptCounter = new AtomicInteger();
    private final StringBuilder buffer = new StringBuilder();
    private volatile UUID currentAttemptId;
    private volatile Future<?> future;
    private int lastCheckpointLength;
    private long lastCheckpointAt = System.currentTimeMillis();

    /**
     * 创建活动生成状态。
     *
     * @param conversation 会话
     * @param user 用户消息
     * @param assistant 待生成回答
     * @param requestId HTTP 请求追踪 ID
     * @param emitter SSE 通道
     * @param control 回答模型流控制器
     */
    private ActiveGeneration(
        Conversation conversation,
        Message user,
        Message assistant,
        String requestId,
        SseEmitter emitter,
        AnswerGenerator.Control control) {
      this.conversation = conversation;
      // 异步线程不读取请求上下文；所有权在通过 Controller 校验后随任务显式捕获。
      this.ownerId = conversation.getOwnerId();
      this.user = user;
      this.generationId = assistant.getId();
      this.assistant = assistant;
      this.requestId = requestId;
      this.emitter = emitter;
      this.control = control;
    }

    /**
     * 返回当前生成所属会话。
     *
     * @return 会话实体
     */
    private Conversation conversation() {
      return conversation;
    }

    /** 关闭模型流并在任务已经运行时中断异步线程。 */
    private void cancel() {
      control.close();
      Future<?> running = future;
      if (this.running.get() && running != null) running.cancel(true);
    }
  }
}
