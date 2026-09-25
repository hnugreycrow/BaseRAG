package com.hnu.backend.conversation.service;

import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.entity.MessageRole;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.conversation.vo.ConversationResponses;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import com.hnu.backend.shared.web.RequestTiming;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 管理会话、轮次和回答版本，并委托异步生成。 */
@Service
public class ConversationService {

  private final ConversationMapper conversationMapper;
  private final MessageMapper messageMapper;
  private final ConversationGenerationService generation;
  private final ConversationMessagePresenter presenter = new ConversationMessagePresenter();

  /**
   * 创建会话管理入口。
   *
   * @param conversationMapper 会话持久化接口
   * @param messageMapper 消息持久化接口
   * @param generation 异步生成协调服务
   */
  public ConversationService(
      ConversationMapper conversationMapper,
      MessageMapper messageMapper,
      ConversationGenerationService generation) {
    this.conversationMapper = conversationMapper;
    this.messageMapper = messageMapper;
    this.generation = generation;
  }

  /** 测试或容器关闭时结束当前生成任务。 */
  void close() {
    generation.close();
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
              .map(presenter::assistantResponse)
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
    if (generation.isActive(id) || messageMapper.countRunning(ownerId, id) > 0) {
      throw ApiException.conflict(ErrorCode.GENERATION_IN_PROGRESS, "请先停止当前生成再删除会话");
    }
    conversationMapper.delete(ownerId, id);
    generation.forgetConversation(id);
  }

  /**
   * 创建用户消息并立即返回生成流；同一客户端消息 ID 的已结束请求会重放终态。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @param clientMessageId 客户端幂等消息标识
   * @param rawQuestion 未归一化的问题
   * @param requestId HTTP 请求追踪 ID
   * @return 实时生成或幂等重放的 SSE 通道
   */
  public SseEmitter ask(
      UUID ownerId,
      UUID conversationId,
      UUID clientMessageId,
      String rawQuestion,
      String requestId) {
    return generation.ask(ownerId, conversationId, clientMessageId, rawQuestion, requestId);
  }

  /**
   * 使用完整请求计时信息创建用户消息并启动生成流。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @param clientMessageId 客户端幂等消息标识
   * @param rawQuestion 未归一化的问题
   * @param timing HTTP 请求起始信息
   * @return 实时生成或幂等重放的 SSE 通道
   */
  public SseEmitter ask(
      UUID ownerId,
      UUID conversationId,
      UUID clientMessageId,
      String rawQuestion,
      RequestTiming timing) {
    return generation.ask(ownerId, conversationId, clientMessageId, rawQuestion, timing);
  }

  /**
   * 为失败或已取消的回答创建新版本并返回生成流。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @param assistantMessageId 原回答标识
   * @param clientRequestId 本次重试的幂等标识
   * @param requestId HTTP 请求追踪 ID
   * @return 新回答版本或幂等重放的 SSE 通道
   */
  public SseEmitter retry(
      UUID ownerId,
      UUID conversationId,
      UUID assistantMessageId,
      UUID clientRequestId,
      String requestId) {
    return generation.retry(
        ownerId, conversationId, assistantMessageId, clientRequestId, requestId);
  }

  /**
   * 使用完整请求计时信息重试失败或已取消的回答。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @param assistantMessageId 原回答标识
   * @param clientRequestId 本次重试的幂等标识
   * @param timing HTTP 请求起始信息
   * @return 新回答版本或幂等重放的 SSE 通道
   */
  public SseEmitter retry(
      UUID ownerId,
      UUID conversationId,
      UUID assistantMessageId,
      UUID clientRequestId,
      RequestTiming timing) {
    return generation.retry(ownerId, conversationId, assistantMessageId, clientRequestId, timing);
  }

  /**
   * 为最后一轮当前成功回答创建新版本。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @param assistantMessageId 原回答标识
   * @param clientRequestId 本次生成的幂等标识
   * @param requestId HTTP 请求追踪 ID
   * @return 新回答版本或幂等重放的 SSE 通道
   */
  public SseEmitter regenerate(
      UUID ownerId,
      UUID conversationId,
      UUID assistantMessageId,
      UUID clientRequestId,
      String requestId) {
    return generation.regenerate(
        ownerId, conversationId, assistantMessageId, clientRequestId, requestId);
  }

  /**
   * 使用完整请求计时信息重新生成最后一轮当前成功回答。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @param assistantMessageId 原回答标识
   * @param clientRequestId 本次生成的幂等标识
   * @param timing HTTP 请求起始信息
   * @return 新回答版本或幂等重放的 SSE 通道
   */
  public SseEmitter regenerate(
      UUID ownerId,
      UUID conversationId,
      UUID assistantMessageId,
      UUID clientRequestId,
      RequestTiming timing) {
    return generation.regenerate(
        ownerId, conversationId, assistantMessageId, clientRequestId, timing);
  }

  /**
   * 取消指定回答；已结束回答重复取消不会改变终态。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @param generationId 回答生成标识
   */
  public void cancel(UUID ownerId, UUID conversationId, UUID generationId) {
    generation.cancel(ownerId, conversationId, generationId);
  }

  /**
   * 停止指定用户的全部活动回答，供账号会话撤销使用。
   *
   * @param ownerId 所属用户标识
   */
  public void cancelByOwner(UUID ownerId) {
    generation.cancelByOwner(ownerId);
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
    if (value.isEmpty()
        || value.length() > 200
        || value.chars().anyMatch(Character::isISOControl)) {
      throw ApiException.bad(ErrorCode.INVALID_CONVERSATION_TITLE, "会话标题应为 1 到 200 个有效字符");
    }
    return value;
  }
}
