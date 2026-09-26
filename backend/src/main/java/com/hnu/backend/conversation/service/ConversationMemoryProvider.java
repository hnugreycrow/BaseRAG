package com.hnu.backend.conversation.service;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.json.JsonCodecs;
import com.hnu.backend.conversation.config.ConversationProperties;
import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.entity.MessageRole;
import com.hnu.backend.conversation.entity.MessageStatus;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.TraceReasonCatalog;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.observability.trace.TraceContext;
import com.hnu.backend.rag.generation.MemorySummaryPrompts;
import com.hnu.backend.rag.memory.MemoryProvider;
import com.hnu.backend.rag.memory.MemoryTurn;
import com.hnu.backend.rag.memory.RagMemory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 从会话持久化数据构造 RAG 中立记忆，并按批次维护简短话题摘要。 */
@Component
public class ConversationMemoryProvider implements MemoryProvider {
  private static final Logger log = LoggerFactory.getLogger(ConversationMemoryProvider.class);
  private final ConversationMapper conversationMapper;
  private final MessageMapper messageMapper;
  private final ConversationProperties config;
  private final ChatClient chat;
  private final JsonMapper json = JsonCodecs.models();

  /**
   * 创建会话记忆适配器。
   *
   * @param conversationMapper 会话及其摘要游标持久化接口
   * @param messageMapper 会话消息读取接口
   * @param config 会话窗口和摘要批次配置
   * @param chat 用于生成简短增量摘要的模型客户端
   */
  public ConversationMemoryProvider(
      ConversationMapper conversationMapper,
      MessageMapper messageMapper,
      ConversationProperties config,
      ChatClient chat) {
    this.conversationMapper = conversationMapper;
    this.messageMapper = messageMapper;
    this.config = config;
    this.chat = chat;
  }

  /**
   * 加载指定轮次之前的有效会话记忆，并在满足批次条件时尝试刷新持久化摘要。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话 ID
   * @param beforeTurn 当前用户消息轮次，返回内容不包含该轮
   * @return 与会话实体隔离的不可变 RAG 记忆
   */
  @Override
  public RagMemory load(UUID ownerId, UUID conversationId, int beforeTurn) {
    return load(ownerId, conversationId, beforeTurn, RagRunTrace.noop());
  }

  /** {@inheritDoc} */
  @Override
  public RagMemory load(UUID ownerId, UUID conversationId, int beforeTurn, TraceContext trace) {
    LoadedMemory loaded =
        trace.execute(
            RagStageName.MEMORY_LOAD,
            null,
            beforeTurn - 1,
            span -> {
              try {
                Conversation value = conversationMapper.find(ownerId, conversationId);
                if (value == null) {
                  throw new IllegalArgumentException("conversation does not exist");
                }
                List<MemoryTurn> history = completeTurns(ownerId, conversationId, beforeTurn);
                span.success(history.size());
                return new LoadedMemory(value, history);
              } catch (RuntimeException error) {
                if (error instanceof ApiException) {
                  span.error(error);
                } else {
                  span.failed(TraceReasonCatalog.MEMORY_LOAD_FAILED.code());
                }
                throw error;
              }
            });
    Conversation conversation = loaded.conversation();
    List<MemoryTurn> turns = loaded.turns();
    conversation = refreshSummaryIfNeeded(conversation, turns, trace);
    RagMemory memory =
        toMemory(
            conversation.getSummaryText(),
            conversation.getSummaryRevision(),
            conversation.getSummarizedThroughTurn(),
            turns);
    int memoryChars = json.writeValueAsString(memory).length();
    log.info(
        "conversation={} memory loaded summaryRevision={} unsummarizedTurns={} recentTurns={} loadedThroughTurn={} memoryChars={}",
        conversationId,
        memory.summaryRevision(),
        memory.unsummarizedTurns().size(),
        memory.recentTurns().size(),
        memory.loadedThroughTurn(),
        memoryChars);
    return memory;
  }

  /** 从摘要覆盖游标、有效完整轮次和最近原文窗口构造提示词记忆。 */
  private RagMemory toMemory(String summary, int revision, int covered, List<MemoryTurn> turns) {
    int recentStart = Math.max(0, turns.size() - config.getRecentTurns());
    List<MemoryTurn> unsummarized =
        turns.subList(0, recentStart).stream().filter(turn -> turn.turnIndex() > covered).toList();
    List<MemoryTurn> recent = turns.subList(recentStart, turns.size());
    int loadedThroughTurn = turns.isEmpty() ? 0 : turns.getLast().turnIndex();
    return new RagMemory(summary, revision, unsummarized, recent, loadedThroughTurn);
  }

  /**
   * 当上次摘要覆盖轮次滑出最近窗口时，更新一批与窗口前半部分重叠的轮次。
   *
   * @param conversation 当前会话和摘要版本
   * @param turns 当前轮之前全部有效完整轮次
   * @param trace 当前问答 Trace
   * @return 更新成功、并发胜出或降级后的会话快照
   */
  private Conversation refreshSummaryIfNeeded(
      Conversation conversation, List<MemoryTurn> turns, TraceContext trace) {
    int recentStart = turns.size() - config.getRecentTurns();
    if (recentStart <= 0) {
      trace.skipped(RagStageName.MEMORY_SUMMARY, null, TraceReasonCatalog.SUMMARY_NOT_DUE.code());
      return conversation;
    }
    int covered = conversation.getSummarizedThroughTurn();
    if (covered >= turns.get(recentStart).turnIndex()) {
      trace.skipped(RagStageName.MEMORY_SUMMARY, null, TraceReasonCatalog.SUMMARY_NOT_DUE.code());
      return conversation;
    }
    int cutoff = Math.min(turns.size(), recentStart + config.getSummaryBatchTurns());
    List<MemoryTurn> eligible = turns.subList(0, cutoff);
    List<MemoryTurn> pending =
        eligible.stream().filter(turn -> turn.turnIndex() > covered).toList();
    int batchSize =
        covered == 0 ? config.getSummaryBatchTurns() + 1 : config.getSummaryBatchTurns();
    if (pending.size() < config.getSummaryBatchTurns()) {
      trace.skipped(RagStageName.MEMORY_SUMMARY, null, TraceReasonCatalog.SUMMARY_NOT_DUE.code());
      return conversation;
    }
    List<MemoryTurn> batch = pending.subList(0, Math.min(pending.size(), batchSize));
    int through = batch.getLast().turnIndex();
    return trace.execute(
        RagStageName.MEMORY_SUMMARY,
        null,
        batch.size(),
        span -> {
          try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("oldSummary", conversation.getSummaryText());
            input.put("completedTurns", batch);
            String prompt = json.writeValueAsString(input);
            ChatClient.Generation generation =
                chat.generate(MemorySummaryPrompts.system(config.getSummaryMaxChars()), prompt);
            span.model(generation.id(), generation.provider(), generation.model());
            String candidate = validateSummary(generation.content());
            int updated =
                conversationMapper.updateSummary(
                    conversation.getOwnerId(),
                    conversation.getId(),
                    candidate,
                    through,
                    conversation.getSummaryRevision());
            if (updated == 1) {
              Conversation refreshed =
                  conversationMapper.find(conversation.getOwnerId(), conversation.getId());
              span.success(1);
              return refreshed == null ? conversation : refreshed;
            }
            Conversation winner =
                conversationMapper.find(conversation.getOwnerId(), conversation.getId());
            span.success(1);
            return winner == null ? conversation : winner;
          } catch (RuntimeException e) {
            String reasonCode = summaryErrorCode(e);
            span.degraded(0, reasonCode);
            log.warn(
                "conversation={} summary degraded reasonCode={} exceptionType={}",
                conversation.getId(),
                reasonCode,
                e.getClass().getSimpleName());
            return conversation;
          }
        });
  }

  /** 将摘要异常归一化为不包含异常正文的稳定原因码。 */
  private String summaryErrorCode(RuntimeException error) {
    if (error instanceof ApiException api) {
      return api.code();
    }
    if (error instanceof IllegalArgumentException) {
      return TraceReasonCatalog.SUMMARY_INVALID_OUTPUT.code();
    }
    return TraceReasonCatalog.SUMMARY_FAILED.code();
  }

  /**
   * 校验模型输出是否是一行受长度约束的纯文本。
   *
   * @param output 模型原始输出
   * @return 归一化的文本摘要，`无` 对应空字符串
   * @throws IllegalArgumentException 输出格式或长度不合法时抛出
   */
  private String validateSummary(String output) {
    if (output == null || output.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("summary contains control characters");
    }
    String candidate = output.strip();
    if (candidate.isEmpty()
        || candidate.startsWith("{")
        || candidate.startsWith("[")
        || candidate.startsWith("#")
        || candidate.startsWith("```")
        || candidate.startsWith("> ")
        || candidate.startsWith("- ")
        || candidate.startsWith("* ")
        || candidate.contains("**")
        || candidate.contains("__")
        || candidate.contains("`")
        || candidate.codePointCount(0, candidate.length()) > config.getSummaryMaxChars()) {
      throw new IllegalArgumentException("invalid summary text");
    }
    return "无".equals(candidate) ? "" : candidate;
  }

  /**
   * 读取并配对当前轮之前的有效用户消息与激活完成回答。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话 ID
   * @param beforeTurn 当前轮次上界
   * @return 按轮次升序排列的完整对话轮次
   */
  private List<MemoryTurn> completeTurns(UUID ownerId, UUID conversationId, int beforeTurn) {
    List<Message> all = messageMapper.list(ownerId, conversationId);
    Map<Integer, Message> users = new LinkedHashMap<>();
    all.stream()
        .filter(message -> message.getRole() == MessageRole.USER)
        .forEach(message -> users.put(message.getTurnIndex(), message));
    List<MemoryTurn> result = new ArrayList<>();
    all.stream()
        .filter(
            message ->
                message.getRole() == MessageRole.ASSISTANT
                    && message.isActive()
                    && message.getStatus() == MessageStatus.COMPLETED
                    && message.getTurnIndex() < beforeTurn)
        .sorted(Comparator.comparingInt(Message::getTurnIndex))
        .forEach(
            assistant -> {
              Message user = users.get(assistant.getTurnIndex());
              if (user != null) {
                result.add(
                    new MemoryTurn(
                        assistant.getTurnIndex(), user.getContent(), assistant.getContent()));
              }
            });
    return List.copyOf(result);
  }

  /** 兼容根 Trace 入口；内部显式传递父节点上下文。 */
  @Override
  public RagMemory load(UUID ownerId, UUID conversationId, int beforeTurn, RagRunTrace trace) {
    return load(ownerId, conversationId, beforeTurn, trace.context());
  }

  /** 会话读取与摘要生成分开计时所需的内存快照。 */
  private record LoadedMemory(Conversation conversation, List<MemoryTurn> turns) {}
}
