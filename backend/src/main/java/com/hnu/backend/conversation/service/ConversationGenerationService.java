package com.hnu.backend.conversation.service;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.entity.MessageRole;
import com.hnu.backend.conversation.entity.MessageStatus;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.GenerationAttemptMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.conversation.vo.ConversationStreamEvents;
import com.hnu.backend.conversation.vo.ConversationStreamEvents.Kind;
import com.hnu.backend.observability.service.RagTraceManager;
import com.hnu.backend.observability.trace.AnswerTraceObserver;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.rag.answer.AnswerGenerator;
import com.hnu.backend.rag.answer.AnswerStage;
import com.hnu.backend.rag.vo.ModelInfoResponse;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import com.hnu.backend.shared.error.SafeExceptionLog;
import com.hnu.backend.shared.web.RequestTiming;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/** 管理会话及回答版本的生成、取消和异步执行。 */
@Service
public class ConversationGenerationService {
  private static final Logger log = LoggerFactory.getLogger(ConversationGenerationService.class);
  private final ConversationMapper conversationMapper;
  private final MessageMapper messageMapper;
  private final GenerationAttemptMapper generationAttemptMapper;
  private final AnswerStage answers;
  private final RagProperties rag;
  private final TransactionTemplate tx;
  private final RagTraceManager traces;
  private final ConversationTerminalWriter terminalWriter;
  private final ConversationGenerationRunner runner;
  private final ConversationGenerationRunner.TerminalCallbacks terminalCallbacks =
      new ConversationGenerationRunner.TerminalCallbacks() {
        @Override
        public void completed(
            ActiveGeneration active,
            String content,
            List<String> citations,
            AnswerGenerator.Generation generation) {
          complete(active, content, citations, generation);
        }

        @Override
        public void cancelled(ActiveGeneration active) {
          cancelTerminal(active);
        }

        @Override
        public void failed(ActiveGeneration active, String code, String message) {
          errorTerminal(active, code, message);
        }
      };
  private final JsonMapper json = JsonMapper.builder().build();
  private final ConversationMessagePresenter presenter = new ConversationMessagePresenter();
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
   * @param answers 最终回答、引用校验和修复阶段
   * @param rag RAG 输入配置
   * @param tx 终态持久化事务模板
   * @param traces 单次问答 Trace 管理器
   * @param terminalWriter 回答终态的事务写入服务
   * @param runner 回答流水线执行器
   */
  public ConversationGenerationService(
      ConversationMapper conversationMapper,
      MessageMapper messageMapper,
      GenerationAttemptMapper generationAttemptMapper,
      AnswerStage answers,
      RagProperties rag,
      TransactionTemplate tx,
      RagTraceManager traces,
      ConversationTerminalWriter terminalWriter,
      ConversationGenerationRunner runner) {
    this.conversationMapper = conversationMapper;
    this.messageMapper = messageMapper;
    this.generationAttemptMapper = generationAttemptMapper;
    this.answers = answers;
    this.rag = rag;
    this.tx = tx;
    this.traces = traces;
    this.terminalWriter = terminalWriter;
    this.runner = runner;
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
   * 新建用户消息和待生成回答，并立即返回 SSE 输出通道。
   *
   * @param ownerId 所属用户标识；异步任务会显式捕获该值
   * @param conversationId 会话 ID
   * @param clientMessageId 客户端幂等消息 ID
   * @param rawQuestion 未归一化用户问题
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
    terminalWriter.cancelStored(ownerId, conversationId, generationId, stored.getContent());
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
    if (assistant == null) {
      throw ApiException.conflict(ErrorCode.MESSAGE_INCOMPLETE, "消息尚未创建回答");
    }
    if (assistant.getStatus() == MessageStatus.PENDING
        || assistant.getStatus() == MessageStatus.STREAMING) {
      throw ApiException.conflict(ErrorCode.GENERATION_IN_PROGRESS, "相同请求正在生成");
    }
    ConversationSseChannel channel = new ConversationSseChannel();
    executor.submit(
        () -> {
          try {
            channel.send(Kind.STARTED, startedPayload(conversation, user, assistant));
            Kind event =
                switch (assistant.getStatus()) {
                  case COMPLETED -> Kind.COMPLETE;
                  case CANCELLED -> Kind.CANCELLED;
                  default -> Kind.ERROR;
                };
            channel.finish(event, terminalPayload(assistant, requestId));
          } catch (RuntimeException ignored) {
            channel.completeWithError(ignored);
          }
        });
    return channel.emitter();
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
    ConversationSseChannel channel = new ConversationSseChannel();
    ActiveGeneration active =
        new ActiveGeneration(
            conversation, user, assistant, requestId, channel, answers.newControl(), trace);
    activeByConversation.put(conversation.getId(), active);
    activeByGeneration.put(assistant.getId(), active);
    channel.emitter().onCompletion(() -> disconnect(active));
    channel.emitter().onTimeout(() -> disconnect(active));
    channel.emitter().onError(ignored -> disconnect(active));
    channel.startHeartbeat(executor, active.terminal::get, () -> disconnect(active));
    active.future = executor.submit(() -> runner.generate(active, terminalCallbacks));
    return channel.emitter();
  }

  /** 客户端断连时取消尚未结束的回答。 */
  private void disconnect(ActiveGeneration active) {
    if (active.terminal.get()) {
      return;
    }
    active.cancel();
    cancelTerminal(active);
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
    if (!active.terminal.compareAndSet(false, true)) {
      return;
    }
    if (generation != null) {
      active.trace.finalAnswer(
          active.answerTrace == null ? null : active.answerTrace.finalModelSpan(),
          generation.modelId(),
          generation.provider(),
          generation.model());
    }
    try {
      String modelInfo =
          generation == null
              ? null
              : json.writeValueAsString(
                  new ModelInfoResponse(
                      generation.modelId(), generation.provider(), generation.model()));
      terminalWriter.complete(
          terminalContext(active, content), json.writeValueAsString(citations), modelInfo);
      finishPersisted(active, Kind.COMPLETE, ErrorCode.INTERNAL_ERROR.code(), "回答保存失败，请重试");
    } catch (RuntimeException e) {
      terminalPersistenceFailed(active, Kind.COMPLETE, e);
      finish(
          active,
          Kind.ERROR,
          terminalEvent(ErrorCode.INTERNAL_ERROR.code(), "回答保存失败，请重试", active.requestId));
    }
  }

  /**
   * 原子持久化取消终态并发送取消事件。
   *
   * @param active 活动生成状态
   */
  private void cancelTerminal(ActiveGeneration active) {
    if (!active.terminal.compareAndSet(false, true)) {
      return;
    }
    active.trace.terminateStages(true, ErrorCode.GENERATION_CANCELLED.code());
    try {
      terminalWriter.cancel(terminalContext(active, active.buffer.toString()));
      finishPersisted(active, Kind.CANCELLED, ErrorCode.GENERATION_CANCELLED.code(), "生成已停止");
    } catch (RuntimeException e) {
      terminalPersistenceFailed(active, Kind.CANCELLED, e);
      finish(
          active,
          Kind.CANCELLED,
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
    if (!active.terminal.compareAndSet(false, true)) {
      return;
    }
    active.trace.terminateStages(false, code);
    try {
      terminalWriter.fail(terminalContext(active, active.buffer.toString()), code, message);
      finishPersisted(active, Kind.ERROR, code, message);
    } catch (RuntimeException e) {
      terminalPersistenceFailed(active, Kind.ERROR, e);
      finish(active, Kind.ERROR, terminalEvent(code, message, active.requestId));
    }
  }

  /** 在终态竞争胜出后冻结回答与思考内容。 */
  private ConversationTerminalWriter.Context terminalContext(
      ActiveGeneration active, String content) {
    return new ConversationTerminalWriter.Context(
        active.ownerId,
        active.generationId,
        active.conversation.getId(),
        content,
        active.reasoningBuffer.toString(),
        active.currentAttemptId,
        active.trace);
  }

  /**
   * 清理活动任务索引并关闭 SSE 通道。
   *
   * @param active 活动生成状态
   * @param event 终态事件名
   * @param payload 终态事件数据
   */
  private void finish(ActiveGeneration active, Kind event, Object payload) {
    activeByConversation.remove(active.conversation.getId(), active);
    activeByGeneration.remove(active.generationId, active);
    active.channel.finish(event, payload);
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
      ActiveGeneration active, Kind fallbackEvent, String fallbackCode, String fallbackMessage) {
    Message stored = messageMapper.find(active.ownerId, active.generationId);
    if (stored == null) {
      finish(active, fallbackEvent, terminalEvent(fallbackCode, fallbackMessage, active.requestId));
      return;
    }
    active.assistant = stored;
    Kind event =
        switch (stored.getStatus()) {
          case COMPLETED -> Kind.COMPLETE;
          case CANCELLED -> Kind.CANCELLED;
          default -> Kind.ERROR;
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
      ActiveGeneration active, Kind terminalEvent, RuntimeException error) {
    log.error(
        "conversation={} generation={} terminalEvent={} code={} exceptionType={} safeStack={}",
        active.conversation.getId(),
        active.generationId,
        terminalEvent.wireName(),
        ErrorCode.TRACE_OR_RESULT_PERSISTENCE_FAILED.code(),
        error.getClass().getSimpleName(),
        SafeExceptionLog.render(error));
  }

  /**
   * 构造 started 事件数据。
   *
   * @param conversation 会话
   * @param user 用户消息
   * @param assistant 回答消息
   * @return 带协议版本和前端关联 ID 的有序对象
   */
  private ConversationStreamEvents.Started startedPayload(
      Conversation conversation, Message user, Message assistant) {
    return ConversationStreamEvents.started(
        conversation.getId(),
        user.getId(),
        assistant.getId(),
        user.getTurnIndex(),
        assistant.getVariantIndex());
  }

  /**
   * 构造包含完整回答快照的终态事件数据。
   *
   * @param assistant 已持久化回答
   * @param requestId HTTP 请求追踪 ID
   * @return 前端兼容终态数据
   */
  private ConversationStreamEvents.PersistedTerminal terminalPayload(
      Message assistant, String requestId) {
    return new ConversationStreamEvents.PersistedTerminal(
        ConversationStreamEvents.SCHEMA_VERSION,
        presenter.assistantResponse(assistant),
        requestId,
        ErrorCode.retryable(assistant.getErrorCode()),
        assistant.getErrorCode(),
        assistant.getErrorMessage());
  }

  /**
   * 构造没有可读取回答快照时使用的终态错误数据。
   *
   * @param code 稳定错误码
   * @param message 用户可读错误信息
   * @param requestId HTTP 请求追踪 ID
   * @return 前端兼容终态数据
   */
  private ConversationStreamEvents.FailureTerminal terminalEvent(
      String code, String message, String requestId) {
    return new ConversationStreamEvents.FailureTerminal(
        ConversationStreamEvents.SCHEMA_VERSION,
        code,
        message,
        requestId,
        ErrorCode.retryable(code));
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
    if (value == null) {
      throw ApiException.notFound(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
    }
    return value;
  }

  /** 返回指定会话是否存在本进程内的活动生成。 */
  boolean isActive(UUID conversationId) {
    return activeByConversation.containsKey(conversationId);
  }

  /** 删除已删除会话的锁对象。 */
  void forgetConversation(UUID conversationId) {
    conversationLocks.remove(conversationId);
  }

  /**
   * 去除问题首尾空白并应用 RAG 最大长度校验。
   *
   * @param raw 原始问题
   * @return 合法用户问题
   */
  private String normalizeQuestion(String raw) {
    String value = raw == null ? "" : raw.strip();
    if (value.isEmpty() || value.length() > rag.getMaxQuestionChars()) {
      throw ApiException.bad(
          ErrorCode.INVALID_QUESTION, "请输入非空问题，长度不能超过 " + rag.getMaxQuestionChars() + " 字符");
    }
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
  static final class ActiveGeneration {
    final UUID ownerId;
    final Conversation conversation;
    final Message user;
    final UUID generationId;
    Message assistant;
    final String requestId;
    final ConversationSseChannel channel;
    final AnswerGenerator.Control control;
    final RagRunTrace trace;
    final AtomicBoolean terminal = new AtomicBoolean();
    final AtomicBoolean running = new AtomicBoolean();
    final AtomicInteger attemptCounter = new AtomicInteger();
    final StringBuilder buffer = new StringBuilder();
    final StringBuilder reasoningBuffer = new StringBuilder();
    volatile UUID currentAttemptId;
    volatile AnswerTraceObserver answerTrace;
    volatile Future<?> future;
    int lastCheckpointLength;
    long lastCheckpointAt = System.currentTimeMillis();

    /**
     * 创建活动生成状态。
     *
     * @param conversation 会话
     * @param user 用户消息
     * @param assistant 待生成回答
     * @param requestId HTTP 请求追踪 ID
     * @param channel SSE 通道
     * @param control 回答模型流控制器
     * @param trace 当前回答版本的显式 Trace
     */
    ActiveGeneration(
        Conversation conversation,
        Message user,
        Message assistant,
        String requestId,
        ConversationSseChannel channel,
        AnswerGenerator.Control control,
        RagRunTrace trace) {
      this.conversation = conversation;
      // 异步线程不读取请求上下文；所有权在通过 Controller 校验后随任务显式捕获。
      this.ownerId = conversation.getOwnerId();
      this.user = user;
      this.generationId = assistant.getId();
      this.assistant = assistant;
      this.requestId = requestId;
      this.channel = channel;
      this.control = control;
      this.trace = trace;
    }

    /**
     * 返回当前生成所属会话。
     *
     * @return 会话实体
     */
    Conversation conversation() {
      return conversation;
    }

    /** 关闭模型流并在任务已经运行时中断异步线程。 */
    void cancel() {
      control.close();
      Future<?> running = future;
      if (this.running.get() && running != null) {
        running.cancel(true);
      }
    }
  }
}
