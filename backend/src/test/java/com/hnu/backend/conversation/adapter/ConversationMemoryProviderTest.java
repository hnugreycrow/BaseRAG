package com.hnu.backend.conversation.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.configuration.ConversationProperties;
import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.rag.memory.MemoryTurn;
import com.hnu.backend.rag.prompt.MemorySummaryPrompts;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class ConversationMemoryProviderTest {
  private static final String VALID_SUMMARY = "用户咨询了制度修订（当时已回答）。待确认：负责人。";

  private final ConversationMapper conversations = mock(ConversationMapper.class);
  private final MessageMapper messages = mock(MessageMapper.class);
  private final ChatClient chat = mock(ChatClient.class);
  private final ConversationProperties config = new ConversationProperties();
  private final ConversationMemoryProvider provider =
      new ConversationMemoryProvider(conversations, messages, config, chat);

  @Test
  void keepsEightRawTurnsBeforeSummaryStarts() {
    Conversation conversation = conversation();
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 8));

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 9);

    assertTrue(memory.unsummarizedTurns().isEmpty());
    assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), indexes(memory.recentTurns()));
    assertEquals(8, memory.loadedThroughTurn());
    verify(chat, never()).generate(anyString(), anyString());
  }

  @Test
  void firstSummaryOverlapsHalfOfTheEightTurnWindow() {
    Conversation conversation = conversation();
    Conversation updated = conversation();
    updated.setId(conversation.getId());
    updated.setOwnerId(conversation.getOwnerId());
    updated.setSummaryText(VALID_SUMMARY);
    updated.setSummarizedThroughTurn(5);
    updated.setSummaryRevision(1);
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation, updated);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 9));
    when(chat.generate(anyString(), anyString())).thenReturn(generation(VALID_SUMMARY));
    when(conversations.updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(5), eq(0)))
        .thenReturn(1);

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 10);

    assertEquals(VALID_SUMMARY, memory.summary());
    assertEquals(1, memory.summaryRevision());
    assertTrue(memory.unsummarizedTurns().isEmpty());
    assertEquals(List.of(2, 3, 4, 5, 6, 7, 8, 9), indexes(memory.recentTurns()));
    verify(conversations)
        .updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(5), eq(0));
    ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
    verify(chat).generate(system.capture(), user.capture());
    assertEquals(MemorySummaryPrompts.system(400), system.getValue());
    var input = JsonMapper.builder().build().readTree(user.getValue());
    assertEquals("", input.path("oldSummary").asString());
    assertEquals(5, input.path("completedTurns").size());
    assertTrue(input.path("summaryMaxChars").isMissingNode());
  }

  @Test
  void refreshesOnceThePreviousCutoffSlidesOutOfTheWindow() {
    Conversation conversation = conversation();
    conversation.setSummaryText(VALID_SUMMARY);
    conversation.setSummarizedThroughTurn(5);
    conversation.setSummaryRevision(1);
    Conversation updated = conversation();
    updated.setId(conversation.getId());
    updated.setOwnerId(conversation.getOwnerId());
    updated.setSummaryText(VALID_SUMMARY);
    updated.setSummarizedThroughTurn(9);
    updated.setSummaryRevision(2);
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation, conversation, updated);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 12), turns(conversation.getId(), 13));
    when(chat.generate(anyString(), anyString())).thenReturn(generation(VALID_SUMMARY));
    when(conversations.updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(9), eq(1)))
        .thenReturn(1);

    var before = provider.load(conversation.getOwnerId(), conversation.getId(), 13);
    assertEquals(List.of(5, 6, 7, 8, 9, 10, 11, 12), indexes(before.recentTurns()));
    verify(chat, never()).generate(anyString(), anyString());

    var after = provider.load(conversation.getOwnerId(), conversation.getId(), 14);
    assertEquals(List.of(6, 7, 8, 9, 10, 11, 12, 13), indexes(after.recentTurns()));
    assertTrue(after.unsummarizedTurns().isEmpty());
    ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
    verify(chat).generate(anyString(), user.capture());
    var input = JsonMapper.builder().build().readTree(user.getValue());
    assertEquals(
        List.of(6, 7, 8, 9),
        java.util.stream.IntStream.range(0, input.path("completedTurns").size())
            .map(index -> input.path("completedTurns").path(index).path("turnIndex").asInt())
            .boxed()
            .toList());
  }

  @Test
  void summarizesOnlyOneBoundedBatchWhenOlderTurnsAccumulate() {
    Conversation conversation = conversation();
    Conversation updated = conversation();
    updated.setId(conversation.getId());
    updated.setOwnerId(conversation.getOwnerId());
    updated.setSummaryText(VALID_SUMMARY);
    updated.setSummarizedThroughTurn(5);
    updated.setSummaryRevision(1);
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation, updated);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 16));
    when(chat.generate(anyString(), anyString())).thenReturn(generation(VALID_SUMMARY));
    when(conversations.updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(5), eq(0)))
        .thenReturn(1);

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 17);

    assertEquals(List.of(6, 7, 8), indexes(memory.unsummarizedTurns()));
    assertEquals(List.of(9, 10, 11, 12, 13, 14, 15, 16), indexes(memory.recentTurns()));
    ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
    verify(chat).generate(anyString(), user.capture());
    assertEquals(
        5, JsonMapper.builder().build().readTree(user.getValue()).path("completedTurns").size());
  }

  @Test
  void continuesAnExistingConversationWithoutResettingItsSummaryCursor() {
    Conversation conversation = conversation();
    conversation.setSummaryText(VALID_SUMMARY);
    conversation.setSummarizedThroughTurn(8);
    conversation.setSummaryRevision(3);
    Conversation updated = conversation();
    updated.setId(conversation.getId());
    updated.setOwnerId(conversation.getOwnerId());
    updated.setSummaryText(VALID_SUMMARY);
    updated.setSummarizedThroughTurn(12);
    updated.setSummaryRevision(4);
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation, updated);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 16));
    when(chat.generate(anyString(), anyString())).thenReturn(generation(VALID_SUMMARY));
    when(conversations.updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(12), eq(3)))
        .thenReturn(1);

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 17);

    assertEquals(4, memory.summaryRevision());
    assertEquals(List.of(9, 10, 11, 12, 13, 14, 15, 16), indexes(memory.recentTurns()));
    ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
    verify(chat).generate(anyString(), user.capture());
    var input = JsonMapper.builder().build().readTree(user.getValue());
    assertEquals(
        List.of(9, 10, 11, 12),
        java.util.stream.IntStream.range(0, input.path("completedTurns").size())
            .map(index -> input.path("completedTurns").path(index).path("turnIndex").asInt())
            .boxed()
            .toList());
  }

  @Test
  void keepsOldCursorAndRawTurnsWhenSummaryGenerationFails() {
    Conversation conversation = conversation();
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 12));
    when(chat.generate(anyString(), anyString())).thenThrow(new RuntimeException("unavailable"));

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 13);

    assertEquals(0, memory.summaryRevision());
    assertEquals(List.of(1, 2, 3, 4), indexes(memory.unsummarizedTurns()));
    verify(conversations, never())
        .updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(5), eq(0));
  }

  @Test
  void rejectsInvalidPlainTextWithoutAdvancingCursor() {
    Conversation conversation = conversation();
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 12));
    List<String> invalidCandidates =
        List.of(
            "",
            "  ",
            "话题一\n话题二",
            "{\"content\":\"话题\"}",
            "```text\n话题\n```",
            "# 话题",
            "- 话题",
            "**话题**",
            "话题".repeat(401));

    for (String candidate : invalidCandidates) {
      clearInvocations(conversations, messages, chat);
      when(chat.generate(anyString(), anyString())).thenReturn(generation(candidate));

      var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 13);

      assertEquals(List.of(1, 2, 3, 4), indexes(memory.unsummarizedTurns()));
    }
    verify(conversations, never())
        .updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(5), eq(0));
  }

  @Test
  void keepsOldCursorWhenSummaryPersistenceFails() {
    Conversation conversation = conversation();
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 12));
    when(chat.generate(anyString(), anyString())).thenReturn(generation(VALID_SUMMARY));
    when(conversations.updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(5), eq(0)))
        .thenThrow(new RuntimeException("database unavailable"));

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 13);

    assertEquals(0, memory.summaryRevision());
    assertEquals(List.of(1, 2, 3, 4), indexes(memory.unsummarizedTurns()));
  }

  @Test
  void usesWinningSummaryAfterOptimisticLockConflict() {
    Conversation conversation = conversation();
    Conversation winner = conversation();
    winner.setId(conversation.getId());
    winner.setOwnerId(conversation.getOwnerId());
    winner.setSummaryText(VALID_SUMMARY);
    winner.setSummarizedThroughTurn(5);
    winner.setSummaryRevision(1);
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation, winner);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 12));
    when(chat.generate(anyString(), anyString())).thenReturn(generation(VALID_SUMMARY));
    when(conversations.updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(5), eq(0)))
        .thenReturn(0);

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 13);

    assertEquals(1, memory.summaryRevision());
    assertEquals(VALID_SUMMARY, memory.summary());
    assertTrue(memory.unsummarizedTurns().isEmpty());
  }

  @Test
  void rejectsOverlongSummaryWithoutAdvancingCursor() {
    Conversation conversation = conversation();
    String largeSummary = "制度".repeat(2500);
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 9));
    when(chat.generate(anyString(), anyString())).thenReturn(generation(largeSummary));

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 10);

    assertEquals(0, memory.summaryRevision());
    verify(conversations, never())
        .updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(5), eq(0));
  }

  @Test
  void acceptsFourHundredUnicodeCharactersIncludingEmoji() {
    Conversation conversation = conversation();
    String summary = "话".repeat(399) + "😀";
    Conversation updated = conversation();
    updated.setSummaryText(summary);
    updated.setSummarizedThroughTurn(5);
    updated.setSummaryRevision(1);
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation, updated);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 9));
    when(chat.generate(anyString(), anyString())).thenReturn(generation(summary));
    when(conversations.updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), eq(summary), eq(5), eq(0)))
        .thenReturn(1);

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 10);

    assertEquals(summary, memory.summary());
    assertEquals(400, summary.codePointCount(0, summary.length()));
  }

  @Test
  void emptySummaryStillAdvancesTheCoverageCursor() {
    Conversation conversation = conversation();
    conversation.setSummaryText("话题：用户称：制度修订");
    Conversation updated = conversation();
    updated.setSummaryText("");
    updated.setSummarizedThroughTurn(5);
    updated.setSummaryRevision(1);
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation, updated);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 9));
    when(chat.generate(anyString(), anyString())).thenReturn(generation("无"));
    when(conversations.updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), eq(""), eq(5), eq(0)))
        .thenReturn(1);

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 10);

    assertEquals("", memory.summary());
    assertEquals(1, memory.summaryRevision());
    assertTrue(memory.unsummarizedTurns().isEmpty());
  }

  @Test
  void keepsMemoryLongerThanTheFormerLimitForTheModel() {
    Conversation conversation = conversation();
    String longAnswer = "完整历史".repeat(13_000);
    List<Message> history = turns(conversation.getId(), 8);
    history.stream()
        .filter(message -> "ASSISTANT".equals(message.getRole()) && message.getTurnIndex() == 8)
        .findFirst()
        .orElseThrow()
        .setContent(longAnswer);
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation);
    when(messages.list(conversation.getOwnerId(), conversation.getId())).thenReturn(history);

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 9);

    assertTrue(JsonMapper.builder().build().writeValueAsString(memory).length() > 48_000);
    assertEquals(longAnswer, memory.recentTurns().getLast().assistantContent());
    verify(chat, never()).generate(anyString(), anyString());
  }

  @Test
  void usesValidTurnOrderAndPreservesDetailedHistoryInSummaryInput() {
    Conversation conversation = conversation();
    conversation.setSummaryText(
        "话题：用户称：准备制度修订；事实与约束：用户称：截止日期2026-09-20，预算不超过3000元；"
            + "决定与偏好：用户称：偏好中文说明；实体与指代：助手曾回答：HNU负责审批；"
            + "未解决事项：用户称：尚未确定负责人");
    Conversation updated = conversation();
    updated.setId(conversation.getId());
    updated.setOwnerId(conversation.getOwnerId());
    updated.setSummaryText("用户讨论制度修订（当时已回答）。约束：截止2026-09-20，预算不超过3000元；" + "偏好：中文说明。待确认：负责人。");
    updated.setSummarizedThroughTurn(7);
    updated.setSummaryRevision(1);
    List<Message> history = new ArrayList<>();
    for (int index : List.of(1, 2, 4, 5, 7, 8, 9, 10, 11)) {
      addTurn(history, conversation.getId(), index, true, "COMPLETED", true, "回答" + index);
    }
    addTurn(history, conversation.getId(), 3, true, "FAILED", true, "失败回答");
    addTurn(history, conversation.getId(), 6, true, "CANCELLED", true, "已取消回答");
    history.stream()
        .filter(message -> "USER".equals(message.getRole()) && message.getTurnIndex() == 1)
        .findFirst()
        .orElseThrow()
        .setContent("准备制度修订，截止2026-09-20，预算不超过3000元；偏好中文，负责人未定");
    history.stream()
        .filter(message -> "ASSISTANT".equals(message.getRole()) && message.getTurnIndex() == 1)
        .findFirst()
        .orElseThrow()
        .setContent("HNU负责审批，仅是助手曾回答的历史陈述");
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation, updated);
    when(messages.list(conversation.getOwnerId(), conversation.getId())).thenReturn(history);
    when(chat.generate(anyString(), anyString())).thenReturn(generation(updated.getSummaryText()));
    when(conversations.updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(7), eq(0)))
        .thenReturn(1);

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 12);

    assertEquals(List.of(2, 4, 5, 7, 8, 9, 10, 11), indexes(memory.recentTurns()));
    assertTrue(memory.unsummarizedTurns().isEmpty());
    assertTrue(memory.summary().contains("2026-09-20"));
    assertTrue(memory.summary().contains("不超过3000元"));
    assertTrue(memory.summary().contains("中文说明"));
    assertFalse(memory.summary().contains("HNU负责审批"));
    ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
    verify(chat).generate(anyString(), user.capture());
    var input = JsonMapper.builder().build().readTree(user.getValue());
    assertEquals(
        List.of(1, 2, 4, 5, 7),
        java.util.stream.IntStream.range(0, input.path("completedTurns").size())
            .map(index -> input.path("completedTurns").path(index).path("turnIndex").asInt())
            .boxed()
            .toList());
    assertTrue(user.getValue().contains("2026-09-20"));
    assertTrue(user.getValue().contains("不超过3000元"));
    assertTrue(user.getValue().contains("负责人未定"));
    assertTrue(user.getValue().contains("助手曾回答的历史陈述"));
    assertEquals(conversation.getSummaryText(), input.path("oldSummary").asString());
  }

  @Test
  void loadsMigratedSummaryTextWithoutRefreshingIt() {
    Conversation conversation = conversation();
    conversation.setSummaryText("话题：用户称：制度修订");
    conversation.setSummarizedThroughTurn(5);
    conversation.setSummaryRevision(1);
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 8));

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 9);

    assertEquals("话题：用户称：制度修订", memory.summary());
    assertEquals(1, memory.summaryRevision());
    verify(chat, never()).generate(anyString(), anyString());
  }

  @Test
  void includesOnlyActiveCompletedPairedAnswersBeforeCurrentTurn() {
    Conversation conversation = conversation();
    List<Message> history = new ArrayList<>();
    addTurn(history, conversation.getId(), 1, true, "COMPLETED", true, "回答1");
    addTurn(history, conversation.getId(), 2, true, "CANCELLED", true, "回答2");
    addTurn(history, conversation.getId(), 3, false, "COMPLETED", true, "旧回答3");
    addAssistant(history, conversation.getId(), 3, true, "COMPLETED", "新回答3");
    addTurn(history, conversation.getId(), 4, true, "FAILED", true, "回答4");
    addAssistant(history, conversation.getId(), 5, true, "COMPLETED", "无用户回答");
    addTurn(history, conversation.getId(), 6, true, "COMPLETED", true, "回答6");
    addTurn(history, conversation.getId(), 7, true, "COMPLETED", true, "当前回答");
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation);
    when(messages.list(conversation.getOwnerId(), conversation.getId())).thenReturn(history);

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 7);

    assertEquals(List.of(1, 3, 6), indexes(memory.recentTurns()));
    assertEquals("新回答3", memory.recentTurns().get(1).assistantContent());
    assertEquals(6, memory.loadedThroughTurn());
  }

  private Conversation conversation() {
    Conversation value = new Conversation();
    value.setId(UUID.randomUUID());
    value.setOwnerId(UUID.randomUUID());
    value.setTitle("测试");
    value.setSummaryText("");
    return value;
  }

  private List<Message> turns(UUID conversationId, int count) {
    List<Message> result = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      addTurn(result, conversationId, i, true, "COMPLETED", true, "回答" + i);
    }
    return result;
  }

  private void addTurn(
      List<Message> result,
      UUID conversationId,
      int turnIndex,
      boolean active,
      String status,
      boolean includeUser,
      String answer) {
    if (includeUser) {
      Message user = new Message();
      user.setId(UUID.randomUUID());
      user.setConversationId(conversationId);
      user.setRole("USER");
      user.setTurnIndex(turnIndex);
      user.setStatus("COMPLETED");
      user.setContent("问题" + turnIndex);
      result.add(user);
    }
    addAssistant(result, conversationId, turnIndex, active, status, answer);
  }

  private void addAssistant(
      List<Message> result,
      UUID conversationId,
      int turnIndex,
      boolean active,
      String status,
      String answer) {
    Message assistant = new Message();
    assistant.setId(UUID.randomUUID());
    assistant.setConversationId(conversationId);
    assistant.setRole("ASSISTANT");
    assistant.setTurnIndex(turnIndex);
    assistant.setVariantIndex(1);
    assistant.setActive(active);
    assistant.setStatus(status);
    assistant.setContent(answer);
    result.add(assistant);
  }

  private List<Integer> indexes(List<MemoryTurn> turns) {
    return turns.stream().map(MemoryTurn::turnIndex).toList();
  }

  private ChatClient.Generation generation(String content) {
    return new ChatClient.Generation(content, "chat", "test", "test-chat");
  }
}
