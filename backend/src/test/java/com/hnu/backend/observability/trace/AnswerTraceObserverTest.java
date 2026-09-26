package com.hnu.backend.observability.trace;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.RagStageStatus;
import com.hnu.backend.rag.generation.AnswerGenerator;
import com.hnu.backend.rag.generation.AnswerStage;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AnswerTraceObserverTest {
  private final RagRunTrace trace =
      new RagRunTrace(UUID.randomUUID(), OffsetDateTime.now(), System.nanoTime());
  private final AnswerGenerator.ModelTarget target =
      new AnswerGenerator.ModelTarget("id", "test", "model");

  @Test
  void fallbackBindsCallbacksAndKeepsFinalAttemptMetrics() {
    var parent = trace.context().start(RagStageName.ANSWER, null, 1);
    AtomicInteger index = new AtomicInteger();
    var observer =
        new AnswerTraceObserver(
            parent.context(),
            AnswerStage.noopObserver(),
            UUID::randomUUID,
            index::incrementAndGet,
            () -> false);
    observer.started(target, AnswerGenerator.AttemptReason.PRIMARY);
    var first = observer.bindAttempt();
    first.requesting(target);
    first.reasoningDelta("reason");
    observer.sent(true, "reason");
    first.failed(target, "", ApiException.upstream(ErrorCode.MODEL_TIMEOUT, "timeout"));
    observer.started(target, AnswerGenerator.AttemptReason.PROVIDER_FALLBACK);
    var second = observer.bindAttempt();
    second.requesting(target);
    first.delta("late");
    first.completed(target, "late", "stop");
    second.delta("");
    second.delta("answer");
    observer.sent(false, "answer");
    second.completed(target, "answer", "stop");
    observer.validationStarted();
    observer.validationCompleted(1);
    parent.success(1);
    trace.finalAnswer(observer.finalModelSpan(), "id", "test", "model");
    var snapshot = trace.finish(RagRunStatus.COMPLETED, null);
    var models =
        snapshot.stages().stream().filter(s -> s.name() == RagStageName.ANSWER_MODEL).toList();
    assertEquals(2, models.size());
    assertEquals(RagStageStatus.FAILED, models.getFirst().status());
    assertNull(models.getFirst().firstAnswerMs());
    assertNotNull(models.getFirst().firstReasoningMs());
    assertEquals(2, models.getLast().attemptIndex());
    assertEquals(models.getLast().firstAnswerMs(), snapshot.modelTtftMs());
    assertNull(models.getLast().firstReasoningMs());
    assertNotEquals(models.getFirst().attemptId(), models.getLast().attemptId());
    assertTrue(snapshot.degraded());
    assertEquals(RagStageStatus.DEGRADED, snapshot.stages().getLast().status());
  }

  @Test
  void failedSendDoesNotSetRunFirstContentAndEmptyCompletionHasNoTtft() {
    var observer =
        new AnswerTraceObserver(
            trace.context(), AnswerStage.noopObserver(), UUID::randomUUID, () -> 1, () -> false);
    observer.started(target, AnswerGenerator.AttemptReason.PRIMARY);
    observer.requesting(target);
    observer.delta("received but not sent");
    observer.failed(target, "", ApiException.cancelled());
    var snapshot = trace.finish(RagRunStatus.CANCELLED, "GENERATION_CANCELLED");
    assertNull(snapshot.firstAnswerMs());
    assertNull(snapshot.endToEndTtftMs());
    assertNotNull(snapshot.stages().getFirst().firstAnswerMs());
    assertEquals(RagStageStatus.CANCELLED, snapshot.stages().getFirst().status());
  }

  @Test
  void citationRepairAndNoContentKeepSeparateSpans() {
    var parent = trace.context().start(RagStageName.ANSWER, null, 1);
    var observer =
        new AnswerTraceObserver(
            parent.context(), AnswerStage.noopObserver(), UUID::randomUUID, () -> 1, () -> false);
    observer.started(target, AnswerGenerator.AttemptReason.PRIMARY);
    observer.requesting(target);
    observer.completed(target, "", "stop");
    observer.validationStarted();
    observer.invalidReferences("INVALID_CITATIONS", true);
    observer.started(target, AnswerGenerator.AttemptReason.CITATION_REPAIR);
    observer.requesting(target);
    observer.delta("fixed");
    observer.completed(target, "fixed", "stop");
    observer.validationStarted();
    observer.validationCompleted(0);
    parent.success(1);
    var snapshot = trace.finish(RagRunStatus.COMPLETED, null);
    assertNull(snapshot.stages().getFirst().ttftMs());
    assertEquals(
        2,
        snapshot.stages().stream()
            .filter(s -> s.name() == RagStageName.CITATION_VALIDATION)
            .count());
    assertTrue(snapshot.stages().stream().anyMatch(s -> "CITATION_REPAIR".equals(s.reasonCode())));
  }
}
