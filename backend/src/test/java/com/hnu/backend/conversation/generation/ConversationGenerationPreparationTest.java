package com.hnu.backend.conversation.generation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.web.RequestTiming;
import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.entity.MessageRole;
import com.hnu.backend.conversation.entity.MessageStatus;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.observability.service.RagTraceManager;
import com.hnu.backend.observability.trace.RagRunTrace;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class ConversationGenerationPreparationTest {
  private final UUID owner = UUID.randomUUID();
  private final UUID clientId = UUID.randomUUID();
  private final Conversation conversation = new Conversation();
  private final ConversationMapper conversations = mock(ConversationMapper.class);
  private final MessageMapper messages = mock(MessageMapper.class);
  private final RagTraceManager traces = mock(RagTraceManager.class);
  private final Connection connection = mock(Connection.class);
  private final RequestTiming timing = new RequestTiming("request", OffsetDateTime.now(), 1L);
  private ConversationGenerationPreparation preparation;

  @BeforeEach
  void setUp() throws SQLException {
    conversation.setId(UUID.randomUUID());
    conversation.setThinkingEnabled(true);
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    preparation =
        new ConversationGenerationPreparation(
            conversations,
            messages,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            traces);
    when(messages.nextTurn(owner, conversation.getId())).thenReturn(3);
    when(messages.insert(any(Message.class)))
        .thenAnswer(
            invocation -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              return 1;
            });
    when(traces.start(any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              return RagRunTrace.noop();
            });
  }

  @Test
  void newTurnCommitsMessagesAndTraceTogether() throws SQLException {
    var prepared = preparation.prepareNew(owner, conversation, clientId, "问题", timing);
    assertEquals(clientId, prepared.user().getClientRequestId());
    assertEquals(MessageRole.USER, prepared.user().getRole());
    assertEquals(MessageStatus.COMPLETED, prepared.user().getStatus());
    assertEquals("问题", prepared.user().getContent());
    assertEquals(3, prepared.user().getTurnIndex());
    assertEquals(3, prepared.assistant().getTurnIndex());
    assertEquals(1, prepared.assistant().getVariantIndex());
    assertEquals(prepared.user().getId(), prepared.assistant().getReplyToId());
    assertEquals(MessageStatus.PENDING, prepared.assistant().getStatus());
    assertTrue(prepared.assistant().isThinkingEnabled());
    var order = inOrder(messages, traces, conversations, connection);
    order.verify(messages).insert(prepared.user());
    order.verify(messages).insert(prepared.assistant());
    order
        .verify(traces)
        .start(
            owner,
            conversation.getId(),
            prepared.user().getId(),
            prepared.assistant().getId(),
            "问题",
            timing);
    order.verify(conversations).touch(owner, conversation.getId());
    order.verify(connection).commit();
    verify(connection, never()).rollback();
  }

  @ParameterizedTest
  @EnumSource(
      value = MessageStatus.class,
      names = {"FAILED", "CANCELLED", "COMPLETED"})
  void createsNextVersionForAllowedRestart(MessageStatus status) throws SQLException {
    Message previous = previous(status);
    var prepared =
        preparation.prepareRestart(
            owner,
            conversation,
            previous.getId(),
            clientId,
            timing,
            status == MessageStatus.COMPLETED);
    assertEquals(clientId, prepared.assistant().getClientRequestId());
    assertEquals(2, prepared.assistant().getTurnIndex());
    assertEquals(4, prepared.assistant().getVariantIndex());
    assertEquals(previous.getReplyToId(), prepared.user().getId());
    assertTrue(prepared.assistant().isActive());
    assertTrue(prepared.assistant().isThinkingEnabled());
    var order = inOrder(messages, connection);
    order.verify(messages).deactivateReplies(owner, prepared.user().getId());
    order.verify(messages).insert(prepared.assistant());
    order.verify(connection).commit();
    verify(messages, times(1)).insert(any(Message.class));
  }

  @Test
  void rejectsRegenerationOfAnOlderTurnBeforeTransaction() throws SQLException {
    Message previous = previous(MessageStatus.COMPLETED);
    previous.setTurnIndex(1);
    assertEquals(
        "REGENERATE_NOT_ALLOWED",
        assertThrows(
                ApiException.class,
                () ->
                    preparation.prepareRestart(
                        owner, conversation, previous.getId(), clientId, timing, true))
            .code());
    verify(messages, never()).deactivateReplies(any(), any());
    verify(connection, never()).setAutoCommit(false);
  }

  @Test
  void traceFailureRollsBackNewMessages() throws SQLException {
    doThrow(new IllegalStateException("trace failed"))
        .when(traces)
        .start(any(), any(), any(), any(), any(), any());
    assertThrows(
        IllegalStateException.class,
        () -> preparation.prepareNew(owner, conversation, clientId, "问题", timing));
    verify(messages, times(2)).insert(any(Message.class));
    verify(connection).rollback();
    verify(connection, never()).commit();
    verifyNoInteractions(conversations);
  }

  @Test
  void insertFailureRollsBackReplyDeactivation() throws SQLException {
    Message previous = previous(MessageStatus.FAILED);
    when(messages.insert(any(Message.class))).thenThrow(new IllegalStateException("insert failed"));
    assertThrows(
        IllegalStateException.class,
        () ->
            preparation.prepareRestart(
                owner, conversation, previous.getId(), clientId, timing, false));
    verify(messages).deactivateReplies(owner, previous.getReplyToId());
    verify(connection).rollback();
    verify(connection, never()).commit();
    verifyNoInteractions(traces, conversations);
  }

  private Message previous(MessageStatus status) {
    Message user = new Message();
    user.setId(UUID.randomUUID());
    user.setTurnIndex(2);
    user.setContent("原问题");
    Message previous = new Message();
    previous.setId(UUID.randomUUID());
    previous.setConversationId(conversation.getId());
    previous.setRole(MessageRole.ASSISTANT);
    previous.setReplyToId(user.getId());
    previous.setTurnIndex(2);
    previous.setActive(true);
    previous.setStatus(status);
    when(messages.find(owner, previous.getId())).thenReturn(previous);
    when(messages.find(owner, user.getId())).thenReturn(user);
    when(messages.nextVariant(owner, user.getId())).thenReturn(4);
    return previous;
  }
}
