package com.hnu.backend.conversation.service;

import com.hnu.backend.configuration.ConversationProperties;
import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.entity.GenerationAttempt;
import com.hnu.backend.conversation.entity.GenerationAttemptStatus;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.entity.MessageRole;
import com.hnu.backend.conversation.entity.MessageStatus;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.GenerationAttemptMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.conversation.vo.ConversationResponses;
import com.hnu.backend.observability.RagExecutionMode;
import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.service.RagTraceManager;
import com.hnu.backend.observability.trace.RagRunTrace;
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
import com.hnu.backend.rag.vo.SourceSnapshotDecoder;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import com.hnu.backend.shared.error.SafeExceptionLog;
import com.hnu.backend.shared.web.RequestTiming;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
  private final ConversationMapper conversationMapper;
  private final MessageMapper messageMapper;
  private final GenerationAttemptMapper generationAttemptMapper;
  private final ConversationContextService conversationContextService;
  private final ExecutionStage executionStage;
  private final DeduplicationStage deduplicationStage;
  private final RerankStage rerankStage;
  private final PromptAssemblyStage prompts;
  private final AnswerStage answers;
  private final RagProperties rag;
  private final ConversationProperties config;
  private final TransactionTemplate tx;
  private final RagTraceManager traces;
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
   * @param conversationMapper 会话持久化接口
   * @param messageMapper 消息持久化接口
   * @param generationAttemptMapper 模型生成尝试持久化接口
   * @param conversationContextService 会话记忆、规划和路由准备服务
   * @param executionStage 子问题执行阶段
   * @param deduplicationStage 证据去重阶段
   * @param rerankStage 证据重排阶段
   * @param prompts 最终提示词组装阶段
   * @param answers 最终回答、引用校验和修复阶段
   * @param rag RAG 输入配置
   * @param config 会话检查点配置
   * @param tx 终态持久化事务模板
   * @param traces 单次问答 Trace 管理器
   */
  public ConversationService(
      ConversationMapper conversationMapper,
      MessageMapper messageMapper,
      GenerationAttemptMapper generationAttemptMapper,
      ConversationContextService conversationContextService,
      ExecutionStage executionStage,
      DeduplicationStage deduplicationStage,
      RerankStage rerankStage,
      PromptAssemblyStage prompts,
      AnswerStage answers,
      RagProperties rag,
      ConversationProperties config,
      TransactionTemplate tx,
      RagTraceManager traces) {
    this.conversationMapper = conversationMapper;
    this.messageMapper = messageMapper;
    this.generationAttemptMapper = generationAttemptMapper;
    this.conversationContextService = conversationContextService;
    this.executionStage = executionStage;
    this.deduplicationStage = deduplicationStage;
    this.rerankStage = rerankStage;
    this.prompts = prompts;
    this.answers = answers;
    this.rag = rag;
    this.config = config;
    this.tx = tx;
    this.traces = traces;
  }

  /** 将进程异常退出时遗留的运行中消息和尝试恢复为终态。 */
  @PostConstruct
  void recoverInterrupted() {
    int recoveredAttempts = generationAttemptMapper.recoverInterrupted();
    int recoveredMessages = messageMapper.recoverInterrupted();
    int recoveredRuns = traces.recoverInterrupted();
    if (recoveredMessages > 0 || recoveredAttempts > 0 || recoveredRuns > 0) {
      log.warn(
          "Recovered interrupted generation state messages={} attempts={} runs={}",
          recoveredMessages,
          recoveredAttempts,
          recoveredRuns);
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
    return create(ownerId, rawTitle, false);
  }

  public ConversationResponses.Summary create(
      UUID ownerId, String rawTitle, boolean thinkingEnabled) {
    String title = normalizeTitle(rawTitle);
    UUID id = UUID.randomUUID();
    conversationMapper.insert(ownerId, id, title, thinkingEnabled);
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
    return conversationMapper.list(ownerId, query, limit).stream().map(this::summary).toList();
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
    List<Message> all = messageMapper.list(ownerId, id);
    return detail(conversation, all);
  }

  public ConversationResponses.TurnPage page(
      UUID ownerId, UUID id, Integer before, Integer after, Integer target, int rawLimit) {
    Conversation conversation = require(ownerId, id);
    if ((before != null ? 1 : 0) + (after != null ? 1 : 0) + (target != null ? 1 : 0) > 1
        || (before != null && before < 1)
        || (after != null && after < 1)
        || (target != null && target < 1)) {
      throw ApiException.bad(ErrorCode.INVALID_REQUEST, "请提供一个有效的轮次游标");
    }
    int limit = Math.max(1, Math.min(rawLimit, 50));
    int latest = Math.max(0, messageMapper.nextTurn(ownerId, id) - 1);
    int last =
        before != null
            ? Math.min(latest, before - 1)
            : target != null ? (int) Math.min(latest, (long) target + limit / 2) : latest;
    int first = Math.max(1, last - limit + 1);
    if (after != null) {
      first = (int) Math.min((long) latest + 1, (long) after + 1);
      last = (int) Math.min(latest, (long) first + limit - 1);
    }
    return new ConversationResponses.TurnPage(
        detail(
            conversation,
            first > last ? List.of() : messageMapper.listRange(ownerId, id, first, last)),
        first > 1 && latest > 0,
        last < latest,
        latest);
  }

  public ConversationResponses.QuestionPage questions(
      UUID ownerId, UUID id, Integer before, int rawLimit) {
    require(ownerId, id);
    int limit = Math.max(1, Math.min(rawLimit, 100));
    List<Message> items =
        messageMapper.questions(
            ownerId, id, before == null ? Integer.MAX_VALUE : before, limit + 1);
    return new ConversationResponses.QuestionPage(
        items.stream()
            .limit(limit)
            .map(
                message ->
                    new ConversationResponses.Question(
                        message.getId(),
                        message.getTurnIndex(),
                        message
                            .getContent()
                            .substring(0, Math.min(120, message.getContent().length()))))
            .toList(),
        items.size() > limit);
  }

  private ConversationResponses.Detail detail(Conversation conversation, List<Message> all) {
    Map<Integer, Message> users = new LinkedHashMap<>();
    Map<Integer, List<Message>> assistants = new LinkedHashMap<>();
    for (Message message : all) {
      if (message.getRole() == MessageRole.USER) {
        users.put(message.getTurnIndex(), message);
      } else {
        assistants
            .computeIfAbsent(message.getTurnIndex(), ignored -> new ArrayList<>())
            .add(message);
      }
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
        conversation.isThinkingEnabled(),
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
    conversationMapper.rename(ownerId, id, normalizeTitle(rawTitle));
    return summary(require(ownerId, id));
  }

  /** 更新会话后续回答的深度思考选择；运行中的回答沿用其创建时的快照。 */
  public ConversationResponses.Summary setThinkingEnabled(UUID ownerId, UUID id, boolean enabled) {
    require(ownerId, id);
    conversationMapper.setThinkingEnabled(ownerId, id, enabled);
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
    if (activeByConversation.containsKey(id) || messageMapper.countRunning(ownerId, id) > 0) {
      throw ApiException.conflict(ErrorCode.GENERATION_IN_PROGRESS, "请先停止当前生成再删除会话");
    }
    conversationMapper.delete(ownerId, id);
    conversationLocks.remove(id);
  }

  /**
   * 新建用户消息和待生成回答，并立即返回 SSE 输出通道。
   *
   * @param ownerId 所属用户标识；异步任务会显式捕获该值
   * @param conversationId 会话 ID
   * @param clientMessageId 客户端幂等消息 ID
   * @param rawQuestion 未归一化用户问题
   * @param timing HTTP 请求起始信息
   * @return 当前生成或历史幂等结果的 SSE 通道
   */
  public SseEmitter ask(
      UUID ownerId,
      UUID conversationId,
      UUID clientMessageId,
      String rawQuestion,
      String requestId) {
    return ask(
        ownerId,
        conversationId,
        clientMessageId,
        rawQuestion,
        new RequestTiming(requestId, OffsetDateTime.now(ZoneOffset.UTC), System.nanoTime()));
  }

  /**
   * 新建用户消息、回答版本和问答 Trace，并立即返回 SSE 输出通道。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @param clientMessageId 客户端幂等消息标识
   * @param rawQuestion 未归一化问题
   * @param timing HTTP 请求起始信息
   * @return 当前生成或历史幂等结果的 SSE 通道
   */
  public SseEmitter ask(
      UUID ownerId,
      UUID conversationId,
      UUID clientMessageId,
      String rawQuestion,
      RequestTiming timing) {
    String question = normalizeQuestion(rawQuestion);
    Object lock = conversationLocks.computeIfAbsent(conversationId, ignored -> new Object());
    synchronized (lock) {
      Conversation conversation = require(ownerId, conversationId);
      Message existing =
          messageMapper.findByClientRequest(ownerId, conversationId, clientMessageId);
      if (existing != null) {
        Message assistant =
            existing.getRole() == MessageRole.USER
                ? messageMapper.latestReply(ownerId, existing.getId())
                : existing;
        return replayOrConflict(conversation, existing, assistant, timing.requestId());
      }
      ensureIdle(ownerId, conversationId);
      PreparedMessages prepared =
          tx.execute(
              ignored -> {
                int turn = messageMapper.nextTurn(ownerId, conversationId);
                Message user = userMessage(conversationId, clientMessageId, turn, question);
                messageMapper.insert(user);
                Message assistant = assistantMessage(conversationId, null, turn, 1, user.getId());
                assistant.setThinkingEnabled(conversation.isThinkingEnabled());
                messageMapper.insert(assistant);
                RagRunTrace trace =
                    traces.start(
                        ownerId, conversationId, user.getId(), assistant.getId(), question, timing);
                conversationMapper.touch(ownerId, conversationId);
                return new PreparedMessages(user, assistant, trace);
              });
      return launch(
          conversation,
          prepared.user(),
          prepared.assistant(),
          timing.requestId(),
          prepared.trace());
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
    return retry(
        ownerId,
        conversationId,
        assistantMessageId,
        clientRequestId,
        new RequestTiming(requestId, OffsetDateTime.now(ZoneOffset.UTC), System.nanoTime()));
  }

  /** 使用完整 HTTP 起始信息重试失败或取消的回答。 */
  public SseEmitter retry(
      UUID ownerId,
      UUID conversationId,
      UUID assistantMessageId,
      UUID clientRequestId,
      RequestTiming timing) {
    return restart(ownerId, conversationId, assistantMessageId, clientRequestId, timing, false);
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
    return regenerate(
        ownerId,
        conversationId,
        assistantMessageId,
        clientRequestId,
        new RequestTiming(requestId, OffsetDateTime.now(ZoneOffset.UTC), System.nanoTime()));
  }

  /** 使用完整 HTTP 起始信息重新生成最后一轮成功回答。 */
  public SseEmitter regenerate(
      UUID ownerId,
      UUID conversationId,
      UUID assistantMessageId,
      UUID clientRequestId,
      RequestTiming timing) {
    return restart(ownerId, conversationId, assistantMessageId, clientRequestId, timing, true);
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
      RequestTiming timing,
      boolean regenerate) {
    Object lock = conversationLocks.computeIfAbsent(conversationId, ignored -> new Object());
    synchronized (lock) {
      Conversation conversation = require(ownerId, conversationId);
      Message duplicate =
          messageMapper.findByClientRequest(ownerId, conversationId, clientRequestId);
      if (duplicate != null) {
        Message user = messageMapper.find(ownerId, duplicate.getReplyToId());
        return replayOrConflict(conversation, user, duplicate, timing.requestId());
      }
      ensureIdle(ownerId, conversationId);
      Message previous = messageMapper.find(ownerId, assistantMessageId);
      if (previous == null
          || !conversationId.equals(previous.getConversationId())
          || previous.getRole() != MessageRole.ASSISTANT) {
        throw ApiException.notFound(ErrorCode.MESSAGE_NOT_FOUND, "回答不存在");
      }
      if (regenerate) {
        int lastTurn = messageMapper.nextTurn(ownerId, conversationId) - 1;
        if (!previous.isActive()
            || previous.getStatus() != MessageStatus.COMPLETED
            || previous.getTurnIndex() != lastTurn) {
          throw ApiException.conflict(ErrorCode.REGENERATE_NOT_ALLOWED, "只能重新生成会话最后一轮的当前成功回答");
        }
      } else if (!(previous.getStatus() == MessageStatus.FAILED
          || previous.getStatus() == MessageStatus.CANCELLED)) {
        throw ApiException.conflict(ErrorCode.RETRY_NOT_ALLOWED, "只能重试失败或已停止的回答");
      }
      Message user = messageMapper.find(ownerId, previous.getReplyToId());
      PreparedAnswer next =
          tx.execute(
              ignored -> {
                messageMapper.deactivateReplies(ownerId, user.getId());
                Message value =
                    assistantMessage(
                        conversationId,
                        clientRequestId,
                        user.getTurnIndex(),
                        messageMapper.nextVariant(ownerId, user.getId()),
                        user.getId());
                value.setThinkingEnabled(conversation.isThinkingEnabled());
                messageMapper.insert(value);
                RagRunTrace trace =
                    traces.start(
                        ownerId,
                        conversationId,
                        user.getId(),
                        value.getId(),
                        user.getContent(),
                        timing);
                conversationMapper.touch(ownerId, conversationId);
                return new PreparedAnswer(value, trace);
              });
      return launch(conversation, user, next.assistant(), timing.requestId(), next.trace());
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

    Message stored = messageMapper.find(ownerId, generationId);
    if (stored == null
        || !conversationId.equals(stored.getConversationId())
        || stored.getRole() != MessageRole.ASSISTANT) {
      throw ApiException.notFound(ErrorCode.GENERATION_NOT_FOUND, "生成任务不存在");
    }
    if (stored.getStatus() != MessageStatus.PENDING
        && stored.getStatus() != MessageStatus.STREAMING) {
      return;
    }
    tx.executeWithoutResult(
        ignored -> {
          int changed =
              messageMapper.cancelRunning(
                  ownerId, generationId, conversationId, stored.getContent());
          generationAttemptMapper.cancelRunning(ownerId, generationId);
          traces.cancelStored(generationId);
          if (changed > 0) conversationMapper.touch(ownerId, conversationId);
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
        || messageMapper.countRunning(ownerId, conversationId) > 0) {
      throw ApiException.conflict(ErrorCode.GENERATION_IN_PROGRESS, "该会话正在生成回答");
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
    if (assistant == null) throw ApiException.conflict(ErrorCode.MESSAGE_INCOMPLETE, "消息尚未创建回答");
    if (assistant.getStatus() == MessageStatus.PENDING
        || assistant.getStatus() == MessageStatus.STREAMING) {
      throw ApiException.conflict(ErrorCode.GENERATION_IN_PROGRESS, "相同请求正在生成");
    }
    SseEmitter emitter = new SseEmitter(0L);
    executor.submit(
        () -> {
          try {
            send(emitter, "started", startedPayload(conversation, user, assistant));
            String event =
                switch (assistant.getStatus()) {
                  case COMPLETED -> "complete";
                  case CANCELLED -> "cancelled";
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
   * @param trace 与回答版本绑定并显式跨线程传递的 Trace
   * @return 实时 SSE 通道
   */
  private SseEmitter launch(
      Conversation conversation,
      Message user,
      Message assistant,
      String requestId,
      RagRunTrace trace) {
    SseEmitter emitter = new SseEmitter(0L);
    ActiveGeneration active =
        new ActiveGeneration(
            conversation, user, assistant, requestId, emitter, answers.newControl(), trace);
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
          active.trace.enabled()
              ? conversationContextService.prepare(
                  active.conversation,
                  active.user.getTurnIndex(),
                  active.user.getContent(),
                  active.trace)
              : conversationContextService.prepare(
                  active.conversation, active.user.getTurnIndex(), active.user.getContent());
      ensureNotCancelled(active);
      String standaloneQuestion = prepared.queryPlan().standaloneQuestion();
      if (prepared.routingPlan().systemChatOnly()) {
        active.trace.executionMode(RagExecutionMode.SYSTEM_CHAT);
        answerSystemChat(active, prepared);
        return;
      }
      active.trace.executionMode(RagExecutionMode.FULL_PIPELINE);
      var execution =
          active.trace.enabled()
              ? executionStage.execute(
                  active.ownerId,
                  prepared.queryPlan(),
                  prepared.routingPlan(),
                  null,
                  active.control::cancelled,
                  active.trace)
              : executionStage.execute(
                  active.ownerId,
                  prepared.queryPlan(),
                  prepared.routingPlan(),
                  null,
                  active.control::cancelled);
      ensureNotCancelled(active);
      RagRunTrace.Span deduplicationSpan =
          active.trace.start(RagStageName.DEDUPLICATION, null, execution.candidates().size());
      var deduplicated = deduplicationStage.execute(execution.candidates(), execution.budget());
      deduplicationSpan.success(deduplicated.candidates().size());
      ensureNotCancelled(active);
      var reranked =
          active.trace.enabled()
              ? rerankStage.execute(
                  prepared.queryPlan(),
                  execution,
                  deduplicated.candidates(),
                  active.control::cancelled,
                  active.trace)
              : rerankStage.execute(
                  prepared.queryPlan(),
                  execution,
                  deduplicated.candidates(),
                  active.control::cancelled);
      ensureNotCancelled(active);
      int promptInputCount =
          reranked.selectedCandidates().size()
              + (int)
                  execution.subQuestions().stream()
                      .filter(result -> result.toolObservation() != null)
                      .count();
      RagRunTrace.Span promptSpan =
          active.trace.start(RagStageName.PROMPT_ASSEMBLY, null, promptInputCount);
      AssembledPrompt prompt =
          prompts.assemblePipeline(
              prepared.memory(),
              active.user.getContent(),
              prepared.queryPlan(),
              prepared.routingPlan(),
              execution,
              reranked.selectedCandidates());
      promptSpan.success(reranked.selectedCandidates().size() + prompt.toolReferenceIds().size());
      // sources 为文档数；evidence_count 仍是进入 Prompt 的原始证据分块数。
      active.trace.evidenceCount(reranked.selectedCandidates().size());
      messageMapper.prepare(
          active.ownerId,
          active.assistant.getId(),
          standaloneQuestion,
          json.writeValueAsString(prompt.sources()));
      ensureNotCancelled(active);
      active.assistant.setRetrievalQuery(standaloneQuestion);
      active.assistant.setSourcesJson(json.writeValueAsString(prompt.sources()));
      AnswerResult answer =
          answers.execute(
              prompt,
              new ConversationAnswerObserver(active),
              active.control,
              active.assistant.isThinkingEnabled());
      complete(active, answer.content(), answer.citations(), answer.generation());
    } catch (ApiException e) {
      if (active.control.cancelled() || ErrorCode.GENERATION_CANCELLED.code().equals(e.code()))
        cancelTerminal(active);
      else errorTerminal(active, e.code(), e.getMessage());
    } catch (RuntimeException e) {
      if (active.control.cancelled()) {
        cancelTerminal(active);
        return;
      }
      log.error(
          "conversation={} generation={} code={} exceptionType={} safeStack={}",
          active.conversation.getId(),
          active.assistant.getId(),
          ErrorCode.INTERNAL_ERROR.code(),
          e.getClass().getSimpleName(),
          SafeExceptionLog.render(e));
      errorTerminal(
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
      ActiveGeneration active, ConversationContextService.PreparedContext prepared) {
    messageMapper.prepare(active.ownerId, active.assistant.getId(), null, "[]");
    ensureNotCancelled(active);
    active.assistant.setRetrievalQuery(null);
    active.assistant.setSourcesJson("[]");
    prepared
        .queryPlan()
        .subQuestions()
        .forEach(
            question ->
                active.trace.skipped(
                    RagStageName.SUBQUESTION_EXECUTION, question.id(), "SYSTEM_CHAT_ROUTED"));
    active.trace.skipped(RagStageName.CANDIDATE_MERGE, null, "SYSTEM_CHAT");
    active.trace.skipped(RagStageName.DEDUPLICATION, null, "SYSTEM_CHAT");
    active.trace.skipped(RagStageName.RERANK, null, "SYSTEM_CHAT");
    RagRunTrace.Span promptSpan = active.trace.start(RagStageName.PROMPT_ASSEMBLY, null, 0);
    AssembledPrompt prompt =
        prompts.assembleSystemChat(
            prepared.memory(),
            active.user.getContent(),
            prepared.queryPlan(),
            prepared.routingPlan());
    promptSpan.success(0);
    active.trace.evidenceCount(0);
    AnswerResult answer =
        answers.execute(
            prompt,
            new ConversationAnswerObserver(active),
            active.control,
            active.assistant.isThinkingEnabled());
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
    public void normalizedAnswer(String content) {
      ensureNotCancelled(active);
      // 一次性替换已流出的正文，并同步检查点，保证断线读取与最终消息内容一致。
      active.buffer.setLength(0);
      active.buffer.append(content);
      messageMapper.checkpoint(active.ownerId, active.assistant.getId(), content);
      active.lastCheckpointLength = content.length() + active.reasoningBuffer.length();
      active.lastCheckpointAt = System.currentTimeMillis();
      send(active.emitter, "reset", event("reason", "CITATION_NORMALIZED"));
      if (!active.reasoningBuffer.isEmpty()) {
        send(active.emitter, "reasoning_delta", event("text", active.reasoningBuffer.toString()));
      }
      send(active.emitter, "delta", event("text", content));
    }

    /** {@inheritDoc} */
    @Override
    public void started(AnswerGenerator.ModelTarget target, AnswerGenerator.AttemptReason reason) {
      ensureNotCancelled(active);
      if (active.attemptCounter.get() > 0 && active.reasoningBuffer.length() > 0) {
        active.reasoningBuffer.setLength(0);
        messageMapper.checkpointReasoning(active.ownerId, active.assistant.getId(), "");
        active.lastCheckpointLength = active.buffer.length();
        active.lastCheckpointAt = System.currentTimeMillis();
        send(active.emitter, "reset", event("reason", "PROVIDER_FALLBACK"));
      }
      active.currentAttemptReason = reason;
      if (reason != AnswerGenerator.AttemptReason.PRIMARY) active.trace.markDegraded();
      int index = active.attemptCounter.incrementAndGet();
      // 消息状态只在首个候选开始时迁移一次；provider fallback 和引用修复沿用 STREAMING。
      if (index == 1
          && messageMapper.markStreaming(active.ownerId, active.assistant.getId()) == 0) {
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
      attempt.setStatus(GenerationAttemptStatus.STREAMING);
      attempt.setContent("");
      attempt.setReasoningContent("");
      generationAttemptMapper.insert(attempt);
      active.currentAttemptId = attempt.getId();
      messageMapper.setModelInfo(
          active.ownerId,
          active.assistant.getId(),
          json.writeValueAsString(
              new ModelInfoResponse(target.id(), target.provider(), target.model())));
    }

    /** {@inheritDoc} */
    @Override
    public void requesting(AnswerGenerator.ModelTarget target) {
      active.currentModelSpan =
          active
              .trace
              .start(RagStageName.ANSWER_MODEL, null, 1)
              .model(target.id(), target.provider(), target.model());
    }

    /** {@inheritDoc} */
    @Override
    public void delta(String text) {
      ensureNotCancelled(active);
      if (active.currentModelSpan != null) active.currentModelSpan.firstContent();
      active.buffer.append(text);
      send(active.emitter, "delta", event("text", text));
      // 只有 SSE 发送成功才算用户真正看到首个增量。
      active.trace.endToEndDeltaSent();
      checkpoint(active);
    }

    @Override
    public void reasoningDelta(String text) {
      ensureNotCancelled(active);
      if (active.currentModelSpan != null) active.currentModelSpan.firstContent();
      active.reasoningBuffer.append(text);
      send(active.emitter, "reasoning_delta", event("text", text));
      active.trace.endToEndDeltaSent();
      checkpoint(active);
    }

    /** {@inheritDoc} */
    @Override
    public void completed(AnswerGenerator.ModelTarget target, String content, String finishReason) {
      generationAttemptMapper.complete(
          active.ownerId, active.currentAttemptId, content, finishReason);
      generationAttemptMapper.saveReasoning(
          active.ownerId, active.currentAttemptId, active.reasoningBuffer.toString());
      if (active.currentModelSpan != null) {
        active.currentModelSpan.success(
            1, active.currentAttemptReason == null ? null : active.currentAttemptReason.name());
      }
    }

    /** {@inheritDoc} */
    @Override
    public void failed(
        AnswerGenerator.ModelTarget target, String partialContent, ApiException error) {
      if (active.currentModelSpan != null) {
        if (active.control.cancelled()) active.currentModelSpan.cancelled(error.code());
        else active.currentModelSpan.failed(error.code());
      }
      if (active.currentAttemptId != null) {
        generationAttemptMapper.fail(
            active.ownerId,
            active.currentAttemptId,
            active.control.cancelled()
                ? GenerationAttemptStatus.CANCELLED
                : GenerationAttemptStatus.FAILED,
            partialContent,
            error.code(),
            error.getMessage());
        generationAttemptMapper.saveReasoning(
            active.ownerId, active.currentAttemptId, active.reasoningBuffer.toString());
      }
    }

    /** {@inheritDoc} */
    @Override
    public void generationSkipped(String reasonCode) {
      active.trace.skipped(RagStageName.ANSWER_MODEL, null, reasonCode);
      active.trace.skipped(RagStageName.CITATION_VALIDATION, null, reasonCode);
    }

    /** {@inheritDoc} */
    @Override
    public void validationStarted() {
      active.citationSpan = active.trace.start(RagStageName.CITATION_VALIDATION, null, 1);
    }

    /** {@inheritDoc} */
    @Override
    public void validationCompleted(int citationCount) {
      if (active.citationSpan != null) active.citationSpan.success(citationCount);
    }

    /** {@inheritDoc} */
    @Override
    public void invalidReferences(String reasonCode, boolean repairScheduled) {
      if (active.citationSpan != null) active.citationSpan.degraded(0, reasonCode);
      if (active.currentAttemptId != null) {
        // 模型流已经正常结束，引用校验发生在其后，因此需要显式作废 COMPLETED 尝试。
        generationAttemptMapper.invalidateCompleted(
            active.ownerId, active.currentAttemptId, reasonCode, "模型返回了非法引用");
      }
      if (!repairScheduled) return;
      // reset 之前同步清空数据库和检查点游标，避免修复流继续沿用首次正文的长度基线。
      active.buffer.setLength(0);
      active.reasoningBuffer.setLength(0);
      messageMapper.checkpoint(active.ownerId, active.assistant.getId(), "");
      messageMapper.checkpointReasoning(active.ownerId, active.assistant.getId(), "");
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
    int generatedChars = active.buffer.length() + active.reasoningBuffer.length();
    if (generatedChars - active.lastCheckpointLength >= config.getCheckpointChars()
        || now - active.lastCheckpointAt >= config.getCheckpointIntervalMs()) {
      messageMapper.checkpoint(active.ownerId, active.assistant.getId(), active.buffer.toString());
      messageMapper.checkpointReasoning(
          active.ownerId, active.assistant.getId(), active.reasoningBuffer.toString());
      if (active.currentAttemptId != null) {
        generationAttemptMapper.checkpoint(
            active.ownerId, active.currentAttemptId, active.buffer.toString());
        generationAttemptMapper.checkpointReasoning(
            active.ownerId, active.currentAttemptId, active.reasoningBuffer.toString());
      }
      active.lastCheckpointLength = generatedChars;
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
      ActiveGeneration active, GenerationAttemptStatus status, String code, String message) {
    if (active.currentAttemptId != null) {
      generationAttemptMapper.fail(
          active.ownerId, active.currentAttemptId, status, active.buffer.toString(), code, message);
      generationAttemptMapper.saveReasoning(
          active.ownerId, active.currentAttemptId, active.reasoningBuffer.toString());
    }
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
    if (generation != null) {
      active.trace.finalAnswer(
          active.currentModelSpan, generation.modelId(), generation.provider(), generation.model());
    }
    RagRunTrace.Span persistenceSpan = active.trace.start(RagStageName.RESULT_PERSISTENCE, null, 1);
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
                messageMapper.complete(
                    active.ownerId,
                    active.generationId,
                    content,
                    json.writeValueAsString(citations),
                    modelInfo);
            if (changed > 0)
              messageMapper.saveReasoning(
                  active.ownerId, active.generationId, active.reasoningBuffer.toString());
            if (changed > 0) conversationMapper.touch(active.ownerId, active.conversation.getId());
            persistenceSpan.success(changed);
            // Trace 阶段批量写入与回答终态共享事务，任一失败都不会留下互相矛盾的状态。
            traces.finish(active.trace, RagRunStatus.COMPLETED, null);
          });
      finishPersisted(active, "complete", ErrorCode.INTERNAL_ERROR.code(), "回答保存失败，请重试");
    } catch (RuntimeException e) {
      persistenceSpan.failed(ErrorCode.TRACE_OR_RESULT_PERSISTENCE_FAILED.code());
      terminalPersistenceFailed(active, "complete", e);
      finish(
          active,
          "error",
          terminalEvent(ErrorCode.INTERNAL_ERROR.code(), "回答保存失败，请重试", active.requestId));
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
    RagRunTrace.Span persistenceSpan = active.trace.start(RagStageName.RESULT_PERSISTENCE, null, 1);
    try {
      tx.executeWithoutResult(
          ignored -> {
            int changed =
                messageMapper.cancelRunning(
                    active.ownerId, active.generationId, active.conversation.getId(), content);
            if (changed > 0)
              messageMapper.saveReasoning(
                  active.ownerId, active.generationId, active.reasoningBuffer.toString());
            generationAttemptMapper.cancelRunning(active.ownerId, active.generationId);
            if (changed > 0) conversationMapper.touch(active.ownerId, active.conversation.getId());
            persistenceSpan.success(changed);
            traces.finish(
                active.trace, RagRunStatus.CANCELLED, ErrorCode.GENERATION_CANCELLED.code());
          });
      finishPersisted(active, "cancelled", ErrorCode.GENERATION_CANCELLED.code(), "生成已停止");
    } catch (RuntimeException e) {
      persistenceSpan.failed(ErrorCode.TRACE_OR_RESULT_PERSISTENCE_FAILED.code());
      terminalPersistenceFailed(active, "cancelled", e);
      finish(
          active,
          "cancelled",
          terminalEvent(ErrorCode.GENERATION_CANCELLED.code(), "生成已停止", active.requestId));
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
    RagRunTrace.Span persistenceSpan = active.trace.start(RagStageName.RESULT_PERSISTENCE, null, 1);
    try {
      tx.executeWithoutResult(
          ignored -> {
            int changed =
                messageMapper.terminalFailure(
                    active.ownerId,
                    active.generationId,
                    MessageStatus.FAILED,
                    content,
                    code,
                    message);
            if (changed > 0) {
              messageMapper.saveReasoning(
                  active.ownerId, active.generationId, active.reasoningBuffer.toString());
              failCurrentAttempt(active, GenerationAttemptStatus.FAILED, code, message);
              conversationMapper.touch(active.ownerId, active.conversation.getId());
            }
            persistenceSpan.success(changed);
            traces.finish(active.trace, RagRunStatus.FAILED, code);
          });
      finishPersisted(active, "error", code, message);
    } catch (RuntimeException e) {
      persistenceSpan.failed(ErrorCode.TRACE_OR_RESULT_PERSISTENCE_FAILED.code());
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
    Message stored = messageMapper.find(active.ownerId, active.generationId);
    if (stored == null) {
      finish(active, fallbackEvent, terminalEvent(fallbackCode, fallbackMessage, active.requestId));
      return;
    }
    active.assistant = stored;
    String event =
        switch (stored.getStatus()) {
          case COMPLETED -> "complete";
          case CANCELLED -> "cancelled";
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
        "conversation={} generation={} terminalEvent={} code={} exceptionType={} safeStack={}",
        active.conversation.getId(),
        active.generationId,
        terminalEvent,
        ErrorCode.TRACE_OR_RESULT_PERSISTENCE_FAILED.code(),
        error.getClass().getSimpleName(),
        SafeExceptionLog.render(error));
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
    value.put("retryable", ErrorCode.retryable(assistant.getErrorCode()));
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
    value.put("retryable", ErrorCode.retryable(code));
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
    List<SourceResponse> sources = new SourceSnapshotDecoder().decode(message.getSourcesJson());
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
        message.getStatus().name(),
        message.getContent(),
        message.isThinkingEnabled(),
        message.getReasoningContent(),
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
    Conversation value = conversationMapper.find(ownerId, id);
    if (value == null) throw ApiException.notFound(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
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
        value.getId(),
        value.getTitle(),
        value.isThinkingEnabled(),
        value.getCreatedAt(),
        value.getUpdatedAt());
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
      throw ApiException.bad(ErrorCode.INVALID_CONVERSATION_TITLE, "会话标题应为 1 到 200 个有效字符");
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
          ErrorCode.INVALID_QUESTION, "请输入非空问题，长度不能超过 " + rag.getMaxQuestionChars() + " 字符");
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
    value.setRole(MessageRole.USER);
    value.setTurnIndex(turn);
    value.setVariantIndex(0);
    value.setActive(true);
    value.setStatus(MessageStatus.COMPLETED);
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
    value.setRole(MessageRole.ASSISTANT);
    value.setTurnIndex(turn);
    value.setVariantIndex(variant);
    value.setActive(true);
    value.setReplyToId(replyTo);
    value.setStatus(MessageStatus.PENDING);
    value.setContent("");
    value.setReasoningContent("");
    value.setSourcesJson("[]");
    value.setCitationsJson("[]");
    return value;
  }

  /**
   * 同一事务中创建的一对用户消息和初始回答。
   *
   * @param user 已持久化用户消息
   * @param assistant 已持久化待生成回答
   * @param trace 与回答版本一一对应的内存 Trace
   */
  private record PreparedMessages(Message user, Message assistant, RagRunTrace trace) {}

  /**
   * 重试或重新生成事务创建的回答与 Trace。
   *
   * @param assistant 新回答版本
   * @param trace 新回答版本的内存 Trace
   */
  private record PreparedAnswer(Message assistant, RagRunTrace trace) {}

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
    private final RagRunTrace trace;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger attemptCounter = new AtomicInteger();
    private final StringBuilder buffer = new StringBuilder();
    private final StringBuilder reasoningBuffer = new StringBuilder();
    private volatile UUID currentAttemptId;
    private volatile AnswerGenerator.AttemptReason currentAttemptReason;
    private volatile RagRunTrace.Span currentModelSpan;
    private volatile RagRunTrace.Span citationSpan;
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
     * @param trace 当前回答版本的显式 Trace
     */
    private ActiveGeneration(
        Conversation conversation,
        Message user,
        Message assistant,
        String requestId,
        SseEmitter emitter,
        AnswerGenerator.Control control,
        RagRunTrace trace) {
      this.conversation = conversation;
      // 异步线程不读取请求上下文；所有权在通过 Controller 校验后随任务显式捕获。
      this.ownerId = conversation.getOwnerId();
      this.user = user;
      this.generationId = assistant.getId();
      this.assistant = assistant;
      this.requestId = requestId;
      this.emitter = emitter;
      this.control = control;
      this.trace = trace;
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
