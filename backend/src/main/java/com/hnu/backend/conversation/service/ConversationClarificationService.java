package com.hnu.backend.conversation.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import com.hnu.backend.common.json.JsonCodecs;
import com.hnu.backend.conversation.entity.*;
import com.hnu.backend.conversation.mapper.*;
import com.hnu.backend.conversation.vo.ClarificationResponse;
import com.hnu.backend.rag.api.ClarificationContext;
import com.hnu.backend.rag.api.RagRequest;
import com.hnu.backend.rag.clarification.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 负责澄清待办的认领、恢复和完成；条件更新和消息写入共用外层事务。 */
@Service
public class ConversationClarificationService {
  private final ConversationClarificationMapper mapper;
  private final ConversationMapper conversations;
  private final MessageMapper messages;
  private final ClarificationDecisionStage decision;
  private final JsonMapper json = JsonCodecs.snapshots();

  /** 装配私有会话校验、待办持久化和自由文本识别。 */
  public ConversationClarificationService(
      ConversationClarificationMapper mapper,
      ConversationMapper conversations,
      MessageMapper messages,
      ClarificationDecisionStage decision) {
    this.mapper = mapper;
    this.conversations = conversations;
    this.messages = messages;
    this.decision = decision;
  }

  private ConversationClarification open(UUID conversationId) {
    return mapper.selectOne(
        Wrappers.<ConversationClarification>lambdaQuery()
            .eq(ConversationClarification::getConversationId, conversationId)
            .in(ConversationClarification::getStatus, "PENDING", "RESUMING"));
  }

  /** 返回当前待办摘要；调用方必须已校验会话归属。 */
  public ClarificationResponse pending(UUID conversationId) {
    var row = open(conversationId);
    return row == null ? null : response(row);
  }

  /** 使用服务端标签构造按钮消息，拒绝过期 ID 和非候选节点。 */
  public String question(
      UUID conversationId, UUID clarificationId, UUID selectedNodeId, String raw) {
    if (clarificationId == null && selectedNodeId == null) {
      return raw;
    }
    var row = open(conversationId);
    if (row == null || !row.getId().equals(clarificationId) || selectedNodeId == null) {
      throw conflict();
    }
    String label =
        context(row.getContextJson()).pending().getFirst().options().stream()
            .filter(option -> option.nodeId().equals(selectedNodeId))
            .map(ClarificationContext.Option::label)
            .findFirst()
            .orElseThrow(ConversationClarificationService::conflict);
    String supplement = raw == null ? "" : raw.strip();
    if (supplement.isEmpty() || supplement.equals(label)) {
      return label;
    }
    // 标签始终来自服务端；保留用户在确认卡片中同时填写的补充条件。
    String prefix = label + "\n补充说明：";
    return supplement.startsWith(prefix) ? supplement : prefix + supplement;
  }

  /** 在创建用户和助手消息的同一事务中认领待办，保存续接上下文。 */
  public void claim(Message user, Message assistant, UUID clarificationId, UUID selectedNodeId) {
    var row = open(user.getConversationId());
    if (row == null) {
      if (clarificationId != null || selectedNodeId != null) {
        throw conflict();
      }
      return;
    }
    if (!"PENDING".equals(row.getStatus())) {
      throw conflict();
    }
    if (clarificationId != null || selectedNodeId != null) {
      question(user.getConversationId(), clarificationId, selectedNodeId, user.getContent());
    }
    var prior = context(row.getContextJson());
    Map<String, UUID> selections = new LinkedHashMap<>(prior.selections());
    if (selectedNodeId != null) {
      selections.put(prior.pending().getFirst().subQuestionId(), selectedNodeId);
    }
    List<UUID> supplements = new ArrayList<>(prior.supplementIds());
    supplements.add(user.getId());
    var next =
        new ClarificationContext(
            prior.originalMessageId(),
            prior.plan(),
            selections,
            prior.pending(),
            supplements,
            prior.totalSteps());
    int changed =
        mapper.update(
            null,
            Wrappers.<ConversationClarification>lambdaUpdate()
                .eq(ConversationClarification::getId, row.getId())
                .eq(ConversationClarification::getStatus, "PENDING")
                .set(ConversationClarification::getStatus, "RESUMING")
                .set(ConversationClarification::getResumeMessageId, user.getId())
                .set(ConversationClarification::getGenerationId, assistant.getId()));
    if (changed != 1) {
      throw conflict();
    }
    saveContext(user, next);
    setDisplayStatus(row, "RESUMING");
  }

  /** 在异步模型调用前恢复原问题，并将自由补充解析为允许的选择。 */
  public RagRequest request(UUID ownerId, Message user, boolean thinking) {
    var saved = context(user.getClarificationContextJson());
    if (saved == null) {
      return new RagRequest(
          ownerId,
          user.getContent(),
          user.getConversationId(),
          user.getTurnIndex(),
          null,
          thinking,
          RagRequest.Mode.CONVERSATION);
    }
    if (!saved.pending().isEmpty()) {
      var first = saved.pending().getFirst();
      if (!saved.selections().containsKey(first.subQuestionId())) {
        UUID selected = decision.resolveText(user.getContent(), first);
        if (selected != null) {
          var choices = new LinkedHashMap<>(saved.selections());
          choices.put(first.subQuestionId(), selected);
          saved =
              new ClarificationContext(
                  saved.originalMessageId(),
                  saved.plan(),
                  choices,
                  saved.pending(),
                  saved.supplementIds(),
                  saved.totalSteps());
          saveContext(user, saved);
        }
      }
    }
    List<String> supplements = new ArrayList<>();
    for (UUID id : saved.supplementIds()) {
      Message supplement = messages.find(ownerId, id);
      if (supplement != null && user.getConversationId().equals(supplement.getConversationId())) {
        supplements.add(supplement.getContent());
      }
    }
    String question =
        saved.plan().standaloneQuestion()
            + (supplements.isEmpty() ? "" : "\n用户补充：\n" + String.join("\n", supplements));
    return new RagRequest(
        ownerId,
        question,
        user.getConversationId(),
        user.getTurnIndex(),
        null,
        thinking,
        RagRequest.Mode.CONVERSATION,
        saved);
  }

  /** 完成消息时原子替换待办，历史澄清仍保留可读快照。 */
  public void complete(UUID generationId, ClarificationContext requested) {
    Message assistant = messages.selectById(generationId);
    var old = open(assistant.getConversationId());
    if (old != null) {
      if (!generationId.equals(old.getGenerationId()) || !"RESUMING".equals(old.getStatus())) {
        throw conflict();
      }
      int changed =
          mapper.update(
              null,
              Wrappers.<ConversationClarification>lambdaUpdate()
                  .eq(ConversationClarification::getId, old.getId())
                  .eq(ConversationClarification::getGenerationId, generationId)
                  .eq(ConversationClarification::getStatus, "RESUMING")
                  .set(ConversationClarification::getStatus, "RESOLVED"));
      if (changed != 1) {
        throw conflict();
      }
      setDisplayStatus(old, "RESOLVED");
    }
    if (requested == null) {
      return;
    }
    UUID originalId =
        requested.originalMessageId() == null
            ? assistant.getReplyToId()
            : requested.originalMessageId();
    var saved =
        new ClarificationContext(
            originalId,
            requested.plan(),
            requested.selections(),
            requested.pending(),
            requested.supplementIds(),
            requested.totalSteps());
    var row = new ConversationClarification();
    row.setId(UUID.randomUUID());
    row.setConversationId(assistant.getConversationId());
    row.setOriginalMessageId(originalId);
    row.setAssistantMessageId(generationId);
    row.setContextJson(json.writeValueAsString(saved));
    row.setStatus("PENDING");
    mapper.insert(row);
    setDisplayStatus(row, "PENDING");
  }

  /** 失败或取消仅释放本次生成持有的待办；补充上下文留在用户消息供重试。 */
  public void release(UUID generationId) {
    var row =
        mapper.selectOne(
            Wrappers.<ConversationClarification>lambdaQuery()
                .eq(ConversationClarification::getGenerationId, generationId)
                .eq(ConversationClarification::getStatus, "RESUMING"));
    if (row != null) {
      Message supplement = messages.selectById(row.getResumeMessageId());
      String saved =
          supplement == null || supplement.getClarificationContextJson() == null
              ? row.getContextJson()
              : supplement.getClarificationContextJson();
      int changed =
          mapper.update(
              null,
              Wrappers.<ConversationClarification>lambdaUpdate()
                  .eq(ConversationClarification::getId, row.getId())
                  .eq(ConversationClarification::getGenerationId, generationId)
                  .eq(ConversationClarification::getStatus, "RESUMING")
                  .set(ConversationClarification::getContextJson, saved)
                  .set(ConversationClarification::getStatus, "PENDING"));
      if (changed == 1) {
        setDisplayStatus(row, "PENDING");
      }
    }
  }

  /** 启动恢复沿用现有单实例恢复策略，只释放已中断生成持有的待办。 */
  @Transactional
  public void recover() {
    for (var row :
        mapper.selectList(
            Wrappers.<ConversationClarification>lambdaQuery()
                .eq(ConversationClarification::getStatus, "RESUMING"))) {
      Message message = messages.selectById(row.getGenerationId());
      if (message == null
          || message.getStatus() == MessageStatus.FAILED
          || message.getStatus() == MessageStatus.CANCELLED) {
        release(row.getGenerationId());
      }
    }
  }

  /** 阻止重生成澄清消息；只允许重试当前待办最后一次失败的续接。 */
  public void checkRestart(Message previous, boolean regenerate) {
    if (previous == null) {
      return;
    }
    if (previous.getClarificationJson() != null) {
      throw conflict();
    }
    // 已被新回答取代的失败续接不能再次认领同一待办。
    Message user = messages.selectById(previous.getReplyToId());
    if (user != null && user.getClarificationContextJson() != null && !previous.isActive()) {
      throw conflict();
    }
    var row = open(previous.getConversationId());
    if (row != null
        && (regenerate
            || !"PENDING".equals(row.getStatus())
            || !previous.getReplyToId().equals(row.getResumeMessageId()))) {
      throw conflict();
    }
  }

  /** 重试失败续接时重新认领待办，不重新解释已经保存的用户选择。 */
  public void restart(Message user, Message assistant) {
    var row = open(user.getConversationId());
    if (row == null) {
      return;
    }
    if (!user.getId().equals(row.getResumeMessageId())
        || mapper.update(
                null,
                Wrappers.<ConversationClarification>lambdaUpdate()
                    .eq(ConversationClarification::getId, row.getId())
                    .eq(ConversationClarification::getStatus, "PENDING")
                    .set(ConversationClarification::getStatus, "RESUMING")
                    .set(ConversationClarification::getGenerationId, assistant.getId()))
            != 1) {
      throw conflict();
    }
    setDisplayStatus(row, "RESUMING");
  }

  /** 幂等取消本人会话的待办；已认领的待办必须先停止生成。 */
  @Transactional
  public void cancel(UUID ownerId, UUID conversationId, UUID id) {
    if (conversations.find(ownerId, conversationId) == null) {
      throw ApiException.notFound(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
    }
    var row = mapper.selectById(id);
    if (row == null || !conversationId.equals(row.getConversationId())) {
      throw ApiException.notFound(ErrorCode.MESSAGE_NOT_FOUND, "澄清不存在");
    }
    if ("RESUMING".equals(row.getStatus())) {
      throw conflict();
    }
    if (!"PENDING".equals(row.getStatus())) {
      return;
    }
    if (mapper.update(
            null,
            Wrappers.<ConversationClarification>lambdaUpdate()
                .eq(ConversationClarification::getId, id)
                .eq(ConversationClarification::getStatus, "PENDING")
                .set(ConversationClarification::getStatus, "CANCELLED"))
        != 1) {
      throw conflict();
    }
    setDisplayStatus(row, "CANCELLED");
  }

  private void saveContext(Message user, ClarificationContext context) {
    user.setClarificationContextJson(json.writeValueAsString(context));
    messages.update(
        null,
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, user.getId())
            .eq(Message::getConversationId, user.getConversationId())
            .set(Message::getClarificationContextJson, user.getClarificationContextJson()));
  }

  private ClarificationContext context(String value) {
    return value == null ? null : json.readValue(value, ClarificationContext.class);
  }

  private ClarificationResponse response(ConversationClarification row) {
    ClarificationContext saved = context(row.getContextJson());
    var current = saved.pending().getFirst();
    String question =
        saved.plan().subQuestions().stream()
            .filter(item -> item.id().equals(current.subQuestionId()))
            .map(com.hnu.backend.rag.pipeline.SubQuestion::question)
            .findFirst()
            .orElse(saved.plan().standaloneQuestion());
    return new ClarificationResponse(
        row.getId(),
        "KB_INTENT",
        row.getStatus(),
        current.options(),
        question,
        saved.totalSteps() - saved.pending().size() + 1,
        saved.totalSteps());
  }

  private void setDisplayStatus(ConversationClarification row, String status) {
    row.setStatus(status);
    messages.update(
        null,
        Wrappers.<Message>lambdaUpdate()
            .eq(Message::getId, row.getAssistantMessageId())
            .eq(Message::getConversationId, row.getConversationId())
            .set(Message::getClarificationJson, json.writeValueAsString(response(row))));
  }

  private static ApiException conflict() {
    return ApiException.conflict(ErrorCode.CLARIFICATION_CONFLICT, "澄清已失效或正在续接，请刷新会话后重试");
  }
}
