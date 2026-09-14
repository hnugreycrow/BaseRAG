package com.hnu.backend.conversation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.rag.model.MemoryTurn;
import com.hnu.backend.rag.model.RagMemory;
import com.hnu.backend.rag.pipeline.stage.MemoryStage;
import com.hnu.backend.rag.port.MemoryProvider;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationContextServiceTest {
  private final MemoryProvider memories = mock(MemoryProvider.class);
  private final ChatClient chat = mock(ChatClient.class);
  private final ConversationContextService service =
      new ConversationContextService(new MemoryStage(memories), chat);

  @Test
  void formatsRagMemoryAndRewritesQuestion() {
    Conversation conversation = conversation();
    RagMemory memory =
        new RagMemory(
            "{\"goalsAndTopics\":[\"用户称：制度\"]}",
            2,
            List.of(new MemoryTurn(4, "较早问题", "较早回答")),
            List.of(new MemoryTurn(5, "最近问题", "最近回答")),
            5);
    when(memories.load(conversation.getId(), 6)).thenReturn(memory);
    when(chat.generate(anyString(), anyString())).thenReturn(generation("独立问题"));

    var prepared = service.prepare(conversation, 6, "它有什么要求？");

    assertEquals("独立问题", prepared.retrievalQuery());
    assertTrue(prepared.history().contains("用户称：制度"));
    assertTrue(prepared.history().contains("[轮次 4]"));
    assertTrue(prepared.history().contains("[轮次 5]"));
    verify(memories).load(conversation.getId(), 6);
  }

  @Test
  void skipsRewriteWhenMemoryHasNoCompletedTurns() {
    Conversation conversation = conversation();
    when(memories.load(conversation.getId(), 1))
        .thenReturn(new RagMemory("{}", 0, List.of(), List.of(), 0));

    var prepared = service.prepare(conversation, 1, "原问题");

    assertEquals("原问题", prepared.retrievalQuery());
    assertFalse(prepared.history().contains("[轮次"));
    verify(chat, never()).generate(anyString(), anyString());
  }

  @Test
  void fallsBackToOriginalQuestionWhenRewriteFails() {
    Conversation conversation = conversation();
    when(memories.load(conversation.getId(), 2))
        .thenReturn(new RagMemory("{}", 0, List.of(), List.of(new MemoryTurn(1, "问题", "回答")), 1));
    when(chat.generate(anyString(), anyString())).thenThrow(new RuntimeException("unavailable"));

    var prepared = service.prepare(conversation, 2, "原问题");

    assertEquals("原问题", prepared.retrievalQuery());
  }

  private Conversation conversation() {
    Conversation value = new Conversation();
    value.setId(UUID.randomUUID());
    return value;
  }

  private ChatClient.Generation generation(String content) {
    return new ChatClient.Generation(content, "chat", "test", "test-chat");
  }
}
