package com.hnu.backend.conversation.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.ai.chat.ChatClient;
import com.hnu.backend.configuration.ConversationProperties;
import com.hnu.backend.conversation.domain.Conversation;
import com.hnu.backend.conversation.domain.Message;
import com.hnu.backend.conversation.infrastructure.persistence.ConversationMapper;
import com.hnu.backend.conversation.infrastructure.persistence.MessageMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationContextServiceTest {
  private final ConversationMapper conversations = mock(ConversationMapper.class);
  private final MessageMapper messages = mock(MessageMapper.class);
  private final ChatClient chat = mock(ChatClient.class);
  private final ConversationProperties config = new ConversationProperties();
  private final ConversationContextService service =
      new ConversationContextService(conversations, messages, config, chat);

  @Test
  void doesNotSummarizeUntilFourTurnsLeaveRecentWindow() {
    Conversation conversation = conversation();
    when(messages.list(conversation.getId())).thenReturn(turns(conversation.getId(), 11));
    when(chat.generate(anyString(), anyString())).thenReturn(generation("独立问题"));

    var prepared = service.prepare(conversation, 12, "它有什么要求？");

    assertEquals("独立问题", prepared.retrievalQuery());
    verify(conversations, never()).updateSummary(any(), anyString(), anyInt(), anyInt());
    verify(chat, times(1)).generate(anyString(), anyString());
  }

  @Test
  void summarizesFirstFourOnceBeforeThirteenthQuestion() {
    Conversation conversation = conversation();
    Conversation updated = conversation();
    updated.setId(conversation.getId());
    updated.setSummaryJson(
        "{\"goalsAndTopics\":[\"用户称：制度\"],\"factsAndConstraints\":[],"
            + "\"decisionsAndPreferences\":[],\"entitiesAndReferences\":[],\"openItems\":[]}");
    updated.setSummarizedThroughTurn(4);
    updated.setSummaryRevision(1);
    when(messages.list(conversation.getId())).thenReturn(turns(conversation.getId(), 12));
    when(chat.generate(anyString(), anyString()))
        .thenReturn(generation(updated.getSummaryJson()), generation("改写后的问题"));
    when(conversations.updateSummary(eq(conversation.getId()), anyString(), eq(4), eq(0)))
        .thenReturn(1);
    when(conversations.find(conversation.getId())).thenReturn(updated);

    var prepared = service.prepare(conversation, 13, "它呢？");

    assertEquals("改写后的问题", prepared.retrievalQuery());
    verify(conversations).updateSummary(eq(conversation.getId()), anyString(), eq(4), eq(0));
    verify(chat, times(2)).generate(anyString(), anyString());
    assertFalse(prepared.history().contains("[轮次 1]"));
    assertTrue(prepared.history().contains("[轮次 5]"));
  }

  @Test
  void keepsUnsummarizedRawHistoryWhenSummaryGenerationFails() {
    Conversation conversation = conversation();
    when(messages.list(conversation.getId())).thenReturn(turns(conversation.getId(), 12));
    when(chat.generate(anyString(), anyString()))
        .thenThrow(new RuntimeException("summary unavailable"))
        .thenReturn(generation("降级检索问题"));

    var prepared = service.prepare(conversation, 13, "它呢？");

    assertEquals("降级检索问题", prepared.retrievalQuery());
    assertTrue(prepared.history().contains("[轮次 1]"));
    verify(conversations, never()).updateSummary(any(), anyString(), anyInt(), anyInt());
  }

  private Conversation conversation() {
    Conversation value = new Conversation();
    value.setId(UUID.randomUUID());
    value.setTitle("测试");
    value.setSummaryJson("{}");
    return value;
  }

  private List<Message> turns(UUID conversationId, int count) {
    List<Message> result = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      Message user = new Message();
      user.setId(UUID.randomUUID());
      user.setConversationId(conversationId);
      user.setRole("USER");
      user.setTurnIndex(i);
      user.setStatus("COMPLETED");
      user.setContent("问题" + i);
      result.add(user);
      Message assistant = new Message();
      assistant.setId(UUID.randomUUID());
      assistant.setConversationId(conversationId);
      assistant.setRole("ASSISTANT");
      assistant.setTurnIndex(i);
      assistant.setVariantIndex(1);
      assistant.setActive(true);
      assistant.setStatus("COMPLETED");
      assistant.setContent("回答" + i);
      result.add(assistant);
    }
    return result;
  }

  private ChatClient.Generation generation(String content) {
    return new ChatClient.Generation(content, "chat", "test", "test-chat");
  }
}
