package com.hnu.backend.conversation.adapter;

import com.hnu.backend.configuration.ConversationProperties;
import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.rag.memory.MemoryProvider;
import com.hnu.backend.rag.memory.MemoryTurn;
import com.hnu.backend.rag.memory.RagMemory;
import com.hnu.backend.rag.prompt.MemorySummaryPrompts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 从会话持久化数据构造 RAG 中立记忆，并按批次维护结构化增量摘要。 */
@Component
public class ConversationMemoryProvider implements MemoryProvider {
  private static final Logger log = LoggerFactory.getLogger(ConversationMemoryProvider.class);
  private static final String EMPTY_SUMMARY =
      "{\"goalsAndTopics\":[],\"factsAndConstraints\":[],\"decisionsAndPreferences\":[],\"entitiesAndReferences\":[],\"openItems\":[]}";
  private static final List<String> SUMMARY_FIELDS =
      List.of(
          "goalsAndTopics",
          "factsAndConstraints",
          "decisionsAndPreferences",
          "entitiesAndReferences",
          "openItems");
  private final ConversationMapper conversations;
  private final MessageMapper messages;
  private final ConversationProperties config;
  private final ChatClient chat;
  private final JsonMapper json = JsonMapper.builder().build();

  /**
   * 创建会话记忆适配器。
   *
   * @param conversations 会话及其摘要游标持久化接口
   * @param messages 会话消息读取接口
   * @param config 会话窗口和摘要批次配置
   * @param chat 用于生成结构化增量摘要的模型客户端
   */
  public ConversationMemoryProvider(
      ConversationMapper conversations,
      MessageMapper messages,
      ConversationProperties config,
      ChatClient chat) {
    this.conversations = conversations;
    this.messages = messages;
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
    Conversation conversation = conversations.find(ownerId, conversationId);
    if (conversation == null) throw new IllegalArgumentException("conversation does not exist");
    List<MemoryTurn> turns = completeTurns(ownerId, conversationId, beforeTurn);
    conversation = refreshSummaryIfNeeded(conversation, turns);
    int covered = conversation.getSummarizedThroughTurn();
    List<MemoryTurn> uncovered = turns.stream().filter(turn -> turn.turnIndex() > covered).toList();
    int recentStart = Math.max(0, uncovered.size() - config.getRecentTurns());
    List<MemoryTurn> unsummarized = uncovered.subList(0, recentStart);
    List<MemoryTurn> recent = uncovered.subList(recentStart, uncovered.size());
    int loadedThroughTurn = turns.isEmpty() ? 0 : turns.getLast().turnIndex();
    RagMemory memory =
        new RagMemory(
            normalizedSummary(conversation.getSummaryJson()),
            conversation.getSummaryRevision(),
            unsummarized,
            recent,
            loadedThroughTurn);
    log.debug(
        "conversation={} memory loaded summaryRevision={} unsummarizedTurns={} recentTurns={} loadedThroughTurn={}",
        conversationId,
        memory.summaryRevision(),
        memory.unsummarizedTurns().size(),
        memory.recentTurns().size(),
        memory.loadedThroughTurn());
    return memory;
  }

  /**
   * 将尚未摘要且已移出最近窗口的完整批次合并到持久化摘要。
   *
   * @param conversation 当前会话和摘要版本
   * @param turns 当前轮之前全部有效完整轮次
   * @return 更新成功、并发胜出或降级后的会话快照
   */
  private Conversation refreshSummaryIfNeeded(Conversation conversation, List<MemoryTurn> turns) {
    int eligibleCount = Math.max(0, turns.size() - config.getRecentTurns());
    List<MemoryTurn> eligible = turns.subList(0, eligibleCount);
    List<MemoryTurn> pending =
        eligible.stream()
            .filter(turn -> turn.turnIndex() > conversation.getSummarizedThroughTurn())
            .toList();
    int batches = pending.size() / config.getSummaryBatchTurns();
    if (batches == 0) return conversation;
    List<MemoryTurn> batch = pending.subList(0, batches * config.getSummaryBatchTurns());
    int through = batch.getLast().turnIndex();
    try {
      Map<String, Object> input = new LinkedHashMap<>();
      input.put("oldSummary", summaryValue(normalizedSummary(conversation.getSummaryJson())));
      input.put("completedTurns", batch);
      String prompt = json.writeValueAsString(input);
      String candidate = chat.generate(MemorySummaryPrompts.system(), prompt).content().strip();
      JsonNode parsed = json.readTree(stripFence(candidate));
      validateSummary(parsed);
      String encoded = json.writeValueAsString(parsed);
      int updated =
          conversations.updateSummary(
              conversation.getOwnerId(),
              conversation.getId(),
              encoded,
              through,
              conversation.getSummaryRevision());
      if (updated == 1) {
        Conversation refreshed =
            conversations.find(conversation.getOwnerId(), conversation.getId());
        return refreshed == null ? conversation : refreshed;
      }
      Conversation winner = conversations.find(conversation.getOwnerId(), conversation.getId());
      return winner == null ? conversation : winner;
    } catch (RuntimeException e) {
      log.warn(
          "conversation={} summary degraded exceptionType={}",
          conversation.getId(),
          e.getClass().getSimpleName());
      return conversation;
    }
  }

  /**
   * 校验模型摘要是否严格符合五字段字符串数组协议。
   *
   * @param node 模型输出解析后的 JSON
   * @throws IllegalArgumentException 输出结构或元素类型不合法时抛出
   */
  private void validateSummary(JsonNode node) {
    if (node == null || !node.isObject() || node.size() != SUMMARY_FIELDS.size()) {
      throw new IllegalArgumentException("invalid summary object");
    }
    for (String key : SUMMARY_FIELDS) {
      JsonNode values = node.path(key);
      if (!values.isArray()) throw new IllegalArgumentException("invalid summary field");
      for (JsonNode value : values) {
        if (!value.isTextual()) throw new IllegalArgumentException("invalid summary item");
      }
    }
  }

  /**
   * 兼容模型偶尔返回的单层 JSON 代码围栏。
   *
   * @param value 模型原始输出
   * @return 去除外层代码围栏后的文本
   */
  private String stripFence(String value) {
    if (!value.startsWith("```")) return value;
    int firstLine = value.indexOf('\n');
    int lastFence = value.lastIndexOf("```");
    return firstLine >= 0 && lastFence > firstLine
        ? value.substring(firstLine + 1, lastFence).strip()
        : value;
  }

  /**
   * 将缺失或旧版空摘要转换为当前五字段空结构。
   *
   * @param value 数据库中的摘要文本
   * @return 可发送给摘要模型的摘要文本
   */
  private String normalizedSummary(String value) {
    return value == null || value.isBlank() || "{}".equals(value) ? EMPTY_SUMMARY : value;
  }

  /**
   * 将合法摘要保留为 JSON 对象，异常历史内容则作为普通字符串放入结构化输入。
   *
   * @param summary 已归一化的摘要文本
   * @return JSON 对象或不具备结构语义的字符串
   */
  private Object summaryValue(String summary) {
    try {
      JsonNode parsed = json.readTree(summary);
      return parsed != null && parsed.isObject() ? parsed : summary;
    } catch (RuntimeException ignored) {
      return summary;
    }
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
    List<Message> all = messages.list(ownerId, conversationId);
    Map<Integer, Message> users = new LinkedHashMap<>();
    all.stream()
        .filter(message -> "USER".equals(message.getRole()))
        .forEach(message -> users.put(message.getTurnIndex(), message));
    List<MemoryTurn> result = new ArrayList<>();
    all.stream()
        .filter(
            message ->
                "ASSISTANT".equals(message.getRole())
                    && message.isActive()
                    && "COMPLETED".equals(message.getStatus())
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
}
