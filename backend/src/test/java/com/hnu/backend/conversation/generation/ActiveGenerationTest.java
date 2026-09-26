package com.hnu.backend.conversation.generation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.rag.generation.AnswerGenerator;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ActiveGenerationTest {
  private final AtomicLong nanos = new AtomicLong();
  private final AnswerGenerator.Control control = mock(AnswerGenerator.Control.class);
  private final ActiveGeneration active = generation();

  @Test
  void cancellationBeforeCompletionFreezesContentAndRejectsLateCallbacks() {
    active.beginAttempt(UUID.randomUUID());
    active.appendContent("已生成正文");
    active.appendReasoning("思考");
    active.cancel();

    assertFalse(active.tryComplete());
    assertTrue(active.tryFinish());
    assertFalse(active.tryFinish());
    assertThrows(ApiException.class, () -> active.appendContent("迟到正文"));
    assertThrows(ApiException.class, () -> active.appendReasoning("迟到思考"));
    assertThrows(ApiException.class, () -> active.beginAttempt(UUID.randomUUID()));
    assertEquals("已生成正文", active.snapshot().content());
    assertEquals("思考", active.snapshot().reasoning());
    active.cancel();
    verify(control, times(1)).close();
  }

  @Test
  void completionBeforeCancellationKeepsItsTerminalClaim() {
    assertTrue(active.tryComplete());
    active.cancel();
    assertFalse(active.cancelled());
    assertFalse(active.tryFinish());
    assertFalse(active.tryComplete());
    verify(control, never()).close();
  }

  @Test
  void competingTerminalCallbacksHaveExactlyOneWinner() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var success =
          executor.submit(
              () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return active.tryComplete();
              });
      var failure =
          executor.submit(
              () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return active.tryFinish();
              });
      try {
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        assertNotEquals(success.get(5, TimeUnit.SECONDS), failure.get(5, TimeUnit.SECONDS));
        assertTrue(active.terminal());
      } finally {
        start.countDown();
      }
    }
  }

  @Test
  void cancellationDuringCloseStillBlocksCompletionWithoutHoldingStateLock() throws Exception {
    CountDownLatch closing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              closing.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return null;
            })
        .when(control)
        .close();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var cancel = executor.submit(active::cancel);
      try {
        assertTrue(closing.await(5, TimeUnit.SECONDS));
        var complete = executor.submit(active::tryComplete);
        assertFalse(complete.get(5, TimeUnit.SECONDS));
        assertTrue(active.tryFinish());
      } finally {
        release.countDown();
      }
      cancel.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void taskRegisteredAfterCancellationIsInterrupted() {
    active.start();
    active.cancel();
    Future<?> task = mock(Future.class);
    active.attachTask(task);
    verify(task).cancel(true);
  }

  @Test
  void taskCancelledBeforeStartCannotEnterPipeline() {
    active.cancel();
    assertThrows(ApiException.class, active::start);
  }

  @Test
  void characterThresholdIncludesReasoningAndOnlyAdvancesAfterSuccessfulSave() {
    active.appendContent("123");
    active.appendReasoning("4");
    assertNull(active.checkpointDue(5, 100));
    active.appendReasoning("5");
    var checkpoint = active.checkpointDue(5, 100);
    assertNotNull(checkpoint);
    // 持久化失败而未确认时，不能吞掉本次检查点。
    assertNotNull(active.checkpointDue(5, 100));
    active.checkpointSaved(checkpoint);
    assertNull(active.checkpointDue(5, 100));
    active.appendContent("6");
    assertEquals("123", checkpoint.content());
  }

  @Test
  void intervalThresholdUsesInjectableMonotonicTime() {
    active.appendContent("a");
    nanos.set(TimeUnit.MILLISECONDS.toNanos(100) - 1);
    assertNull(active.checkpointDue(1000, 100));
    nanos.incrementAndGet();
    var checkpoint = active.checkpointDue(1000, 100);
    assertNotNull(checkpoint);
    active.checkpointSaved(checkpoint);
    assertNull(active.checkpointDue(1000, 100));
    active.tryFinish();
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
    assertNull(active.checkpointDue(1000, 100));
  }

  @Test
  void repairResetsBothBuffersAndCheckpointBaseline() {
    UUID first = UUID.randomUUID();
    assertEquals(1, active.beginAttempt(first));
    active.appendContent("invalid");
    active.appendReasoning("reasoning");
    active.checkpointSaved(active.snapshot());
    active.resetContent();
    active.checkpointSaved(active.snapshot());
    UUID second = UUID.randomUUID();
    assertEquals(2, active.beginAttempt(second));
    active.appendContent("fixed");
    var checkpoint = active.checkpointDue(5, 100);
    assertNotNull(checkpoint);
    assertEquals("", checkpoint.reasoning());
    assertEquals(second, checkpoint.attemptId());
    active.replaceContent("normalized");
    assertEquals("normalized", active.snapshot().content());
  }

  private ActiveGeneration generation() {
    Conversation conversation = new Conversation();
    conversation.setId(UUID.randomUUID());
    conversation.setOwnerId(UUID.randomUUID());
    Message assistant = new Message();
    assistant.setId(UUID.randomUUID());
    return new ActiveGeneration(
        conversation,
        new Message(),
        assistant,
        "request",
        new ConversationSseChannel(),
        control,
        RagRunTrace.noop(),
        nanos::get);
  }
}
