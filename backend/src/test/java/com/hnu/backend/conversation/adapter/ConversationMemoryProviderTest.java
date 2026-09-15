package com.hnu.backend.conversation.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
  private static final String VALID_SUMMARY =
      "{\"goalsAndTopics\":[\"用户称：制度\"],\"factsAndConstraints\":[],"
          + "\"decisionsAndPreferences\":[],\"entitiesAndReferences\":[],\"openItems\":[]}";

  private final ConversationMapper conversations = mock(ConversationMapper.class);
  private final MessageMapper messages = mock(MessageMapper.class);
  private final ChatClient chat = mock(ChatClient.class);
  private final ConversationProperties config = new ConversationProperties();
  private final ConversationMemoryProvider provider =
      new ConversationMemoryProvider(conversations, messages, config, chat);

  @Test
  void separatesOlderUncoveredTurnsFromRecentWindow() {
    Conversation conversation = conversation();
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 11));

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 12);

    assertEquals(List.of(1, 2, 3), indexes(memory.unsummarizedTurns()));
    assertEquals(List.of(4, 5, 6, 7, 8, 9, 10, 11), indexes(memory.recentTurns()));
    assertEquals(11, memory.loadedThroughTurn());
    verify(chat, never()).generate(anyString(), anyString());
  }

  @Test
  void summarizesAllCompleteBatchesAndAdvancesCursor() {
    Conversation conversation = conversation();
    Conversation updated = conversation();
    updated.setId(conversation.getId());
    updated.setOwnerId(conversation.getOwnerId());
    updated.setSummaryJson(VALID_SUMMARY);
    updated.setSummarizedThroughTurn(8);
    updated.setSummaryRevision(1);
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation, updated);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 16));
    when(chat.generate(anyString(), anyString())).thenReturn(generation(VALID_SUMMARY));
    when(conversations.updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(8), eq(0)))
        .thenReturn(1);

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 17);

    assertEquals(VALID_SUMMARY, memory.summary());
    assertEquals(1, memory.summaryRevision());
    assertTrue(memory.unsummarizedTurns().isEmpty());
    assertEquals(List.of(9, 10, 11, 12, 13, 14, 15, 16), indexes(memory.recentTurns()));
    verify(conversations)
        .updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(8), eq(0));
    ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
    verify(chat).generate(system.capture(), user.capture());
    assertEquals(MemorySummaryPrompts.system(), system.getValue());
    var input = JsonMapper.builder().build().readTree(user.getValue());
    assertTrue(input.path("oldSummary").isObject());
    assertEquals(8, input.path("completedTurns").size());
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
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(4), eq(0));
  }

  @Test
  void rejectsInvalidSummarySchemasWithoutAdvancingCursor() {
    Conversation conversation = conversation();
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 12));
    List<String> invalidCandidates =
        List.of(
            "not json",
            "{\"goalsAndTopics\":[],\"factsAndConstraints\":[],"
                + "\"decisionsAndPreferences\":[],\"entitiesAndReferences\":[]}",
            "{\"goalsAndTopics\":[1],\"factsAndConstraints\":[],"
                + "\"decisionsAndPreferences\":[],\"entitiesAndReferences\":[],\"openItems\":[]}",
            VALID_SUMMARY.substring(0, VALID_SUMMARY.length() - 1) + ",\"extra\":[]}");

    for (String candidate : invalidCandidates) {
      clearInvocations(conversations, messages, chat);
      when(chat.generate(anyString(), anyString())).thenReturn(generation(candidate));

      var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 13);

      assertEquals(List.of(1, 2, 3, 4), indexes(memory.unsummarizedTurns()));
    }
    verify(conversations, never())
        .updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(4), eq(0));
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
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(4), eq(0)))
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
    winner.setSummaryJson(VALID_SUMMARY);
    winner.setSummarizedThroughTurn(4);
    winner.setSummaryRevision(1);
    when(conversations.find(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(conversation, winner);
    when(messages.list(conversation.getOwnerId(), conversation.getId()))
        .thenReturn(turns(conversation.getId(), 12));
    when(chat.generate(anyString(), anyString())).thenReturn(generation(VALID_SUMMARY));
    when(conversations.updateSummary(
            eq(conversation.getOwnerId()), eq(conversation.getId()), anyString(), eq(4), eq(0)))
        .thenReturn(0);

    var memory = provider.load(conversation.getOwnerId(), conversation.getId(), 13);

    assertEquals(1, memory.summaryRevision());
    assertEquals(VALID_SUMMARY, memory.summary());
    assertTrue(memory.unsummarizedTurns().isEmpty());
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
    value.setSummaryJson("{}");
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
