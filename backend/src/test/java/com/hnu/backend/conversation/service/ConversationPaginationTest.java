package com.hnu.backend.conversation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.entity.MessageRole;
import com.hnu.backend.conversation.generation.ConversationGenerationService;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConversationPaginationTest {
  private final UUID owner = UUID.randomUUID();
  private final UUID id = UUID.randomUUID();
  private final ConversationMapper conversations = mock(ConversationMapper.class);
  private final MessageMapper messages = mock(MessageMapper.class);
  private final ConversationService service =
      new ConversationService(conversations, messages, mock(ConversationGenerationService.class));

  @AfterEach
  void close() {
    service.close();
  }

  private void seed(int latest) {
    Conversation conversation = new Conversation();
    conversation.setId(id);
    when(conversations.find(owner, id)).thenReturn(conversation);
    when(messages.nextTurn(owner, id)).thenReturn(latest + 1);
  }

  @Test
  void loadsOnlyLatestWindowAndNeverFullHistory() {
    seed(45);
    var page = service.page(owner, id, null, null, null, 20);
    assertTrue(page.hasOlder());
    assertFalse(page.hasNewer());
    assertEquals(45, page.latestTurnIndex());
    verify(messages).listRange(owner, id, 26, 45);
    verify(messages, never()).list(any(), any());
  }

  @Test
  void supportsOlderNewerAndDirectTargetWindows() {
    seed(80);
    service.page(owner, id, 61, null, null, 20);
    verify(messages).listRange(owner, id, 41, 60);
    service.page(owner, id, null, 20, null, 20);
    verify(messages).listRange(owner, id, 21, 40);
    var target = service.page(owner, id, null, null, 7, 20);
    verify(messages).listRange(owner, id, 1, 17);
    assertFalse(target.hasOlder());
    assertTrue(target.hasNewer());
  }

  @Test
  void capsWindowAndHandlesEmptyConversation() {
    seed(80);
    service.page(owner, id, null, null, null, 999);
    verify(messages).listRange(owner, id, 31, 80);
    seed(0);
    var empty = service.page(owner, id, null, null, null, 20);
    assertTrue(empty.conversation().turns().isEmpty());
    assertFalse(empty.hasOlder());
    assertFalse(empty.hasNewer());
  }

  @Test
  void rejectsInvalidCursorsAndChecksOwnershipBeforeReadingMessages() {
    assertThrows(ApiException.class, () -> service.page(owner, id, null, null, null, 20));
    assertThrows(ApiException.class, () -> service.questions(owner, id, null, 50));
    verifyNoInteractions(messages);
    seed(10);
    assertThrows(ApiException.class, () -> service.page(owner, id, 5, 2, null, 20));
    assertThrows(ApiException.class, () -> service.page(owner, id, null, null, -1, 20));
  }

  @Test
  void directoryReturnsBoundedPreviewsAndNextPageFlag() {
    seed(3);
    Message question = new Message();
    question.setId(UUID.randomUUID());
    question.setTurnIndex(3);
    question.setRole(MessageRole.USER);
    question.setContent("问".repeat(200));
    when(messages.questions(owner, id, Integer.MAX_VALUE, 2))
        .thenReturn(List.of(question, question));
    var page = service.questions(owner, id, null, 1);
    assertEquals(1, page.items().size());
    assertEquals(120, page.items().getFirst().preview().length());
    assertTrue(page.hasMore());
  }
}
