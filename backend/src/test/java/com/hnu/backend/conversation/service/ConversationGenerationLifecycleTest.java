package com.hnu.backend.conversation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.GenerationAttemptMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.observability.service.RagTraceManager;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.rag.answer.AnswerGenerator;
import com.hnu.backend.rag.answer.AnswerStage;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class ConversationGenerationLifecycleTest {
  private final UUID ownerId = UUID.randomUUID();
  private final UUID conversationId = UUID.randomUUID();
  private final ConversationMapper conversations = mock(ConversationMapper.class);
  private final MessageMapper messages = mock(MessageMapper.class);
  private final ConversationTerminalWriter writer = mock(ConversationTerminalWriter.class);
  private final ConversationGenerationRunner runner = mock(ConversationGenerationRunner.class);
  private final LinkedBlockingQueue<Running> started = new LinkedBlockingQueue<>();
  private ConversationGenerationService service;

  @BeforeEach
  void setUp() {
    Conversation conversation = new Conversation();
    conversation.setId(conversationId);
    conversation.setOwnerId(ownerId);
    when(conversations.find(ownerId, conversationId)).thenReturn(conversation);
    TransactionTemplate tx = mock(TransactionTemplate.class);
    when(tx.execute(any()))
        .thenAnswer(
            invocation -> {
              TransactionCallback<?> callback = invocation.getArgument(0);
              return callback.doInTransaction(mock(TransactionStatus.class));
            });
    AnswerStage answers = mock(AnswerStage.class);
    when(answers.newControl()).thenAnswer(ignored -> mock(AnswerGenerator.Control.class));
    RagTraceManager traces = mock(RagTraceManager.class);
    when(traces.start(any(), any(), any(), any(), any(), any())).thenReturn(RagRunTrace.noop());
    doAnswer(
            invocation -> {
              ActiveGeneration active = invocation.getArgument(0);
              active.start();
              started.add(new Running(active, invocation.getArgument(1)));
              return null;
            })
        .when(runner)
        .generate(any(), any());
    service =
        new ConversationGenerationService(
            conversations,
            messages,
            mock(GenerationAttemptMapper.class),
            answers,
            new RagProperties(),
            tx,
            traces,
            writer,
            runner);
  }

  @AfterEach
  void close() {
    service.close();
  }

  @Test
  void persistenceFailureDoesNotReopenTerminalAndReleasesConversation() throws Exception {
    doThrow(new IllegalStateException("persistence failed"))
        .when(writer)
        .complete(any(), anyString(), any());
    Running running = launch();
    running.active().appendContent("answer");
    running.callbacks().completed(running.active(), "answer", List.of(), null);

    assertTrue(running.active().terminal());
    running.callbacks().failed(running.active(), "INTERNAL_ERROR", "late failure");
    running.callbacks().cancelled(running.active());
    running.callbacks().completed(running.active(), "duplicate", List.of(), null);
    verify(writer, times(1)).complete(any(), anyString(), any());
    verify(writer, never()).fail(any(), anyString(), anyString());
    verify(writer, never()).cancel(any());

    // 失败后索引必须释放，否则同一会话的下一次请求会被误判为仍在生成。
    Running next = launch();
    assertNotEquals(running.active().generationId(), next.active().generationId());
    next.callbacks().cancelled(next.active());
  }

  @Test
  void cancellationPersistsOneConsistentSnapshotAndIgnoresLateCompletion() throws Exception {
    Running running = launch();
    UUID attemptId = UUID.randomUUID();
    running.active().beginAttempt(attemptId);
    running.active().appendContent("partial");
    running.active().appendReasoning("reasoning");
    service.cancel(ownerId, conversationId, running.active().generationId());
    running.callbacks().completed(running.active(), "late answer", List.of(), null);
    running.callbacks().cancelled(running.active());

    var context = org.mockito.ArgumentCaptor.forClass(ConversationTerminalWriter.Context.class);
    verify(writer, times(1)).cancel(context.capture());
    assertEquals("partial", context.getValue().content());
    assertEquals("reasoning", context.getValue().reasoning());
    assertEquals(attemptId, context.getValue().currentAttemptId());
    verify(writer, never()).complete(any(), anyString(), any());
  }

  private Running launch() throws InterruptedException {
    service.ask(ownerId, conversationId, UUID.randomUUID(), "question", "request");
    Running running = started.poll(5, TimeUnit.SECONDS);
    assertNotNull(running, "流水线必须已启动");
    return running;
  }

  private record Running(
      ActiveGeneration active, ConversationGenerationRunner.TerminalCallbacks callbacks) {}
}
