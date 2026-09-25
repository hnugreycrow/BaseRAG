package com.hnu.backend.observability.trace;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.RagStageStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RagRunTraceTest {
  @Test
  void wrapperPreservesDeclaredResultsAndPropagatesOriginalFailure() {
    RagRunTrace trace = trace();
    var original = new IllegalStateException("failure");
    trace
        .context()
        .execute(
            RagStageName.PLANNING,
            null,
            1,
            parent -> {
              parent
                  .context()
                  .execute(
                      RagStageName.QUERY_PLANNING,
                      null,
                      1,
                      child -> {
                        child.degraded(1, "INVALID_PLAN");
                        return "fallback";
                      });
              parent
                  .context()
                  .execute(
                      RagStageName.INTENT_ROUTING,
                      null,
                      1,
                      child -> {
                        child.skipped(0, "DISABLED");
                        return null;
                      });
              return null;
            });
    assertSame(
        original,
        assertThrows(
            IllegalStateException.class,
            () ->
                trace
                    .context()
                    .execute(
                        RagStageName.RERANK,
                        null,
                        1,
                        span -> {
                          throw original;
                        })));
    var snapshot = trace.finish(RagRunStatus.FAILED, "INTERNAL_ERROR");
    var parent =
        snapshot.stages().stream()
            .filter(s -> s.name() == RagStageName.PLANNING)
            .findFirst()
            .orElseThrow();
    assertEquals(RagStageStatus.DEGRADED, parent.status());
    assertEquals(
        2, snapshot.stages().stream().filter(s -> parent.id().equals(s.parentStageId())).count());
    assertTrue(snapshot.stages().stream().anyMatch(s -> s.status() == RagStageStatus.SKIPPED));
    assertEquals(4, snapshot.stages().size());
  }

  @Test
  void rejectsUnknownParentAndDoesNotReopenClosedScope() {
    RagRunTrace trace = trace();
    assertThrows(
        IllegalArgumentException.class,
        () -> new TraceContext(trace, UUID.randomUUID()).start(RagStageName.EMBEDDING, "Q1", 1));
    var parent = trace.context().start(RagStageName.RETRIEVAL, null, 1);
    parent.success(0);
    parent.context().start(RagStageName.EMBEDDING, "Q1", 1).success(1);
    assertEquals(1, trace.finish(RagRunStatus.COMPLETED, null).stages().size());
  }

  @Test
  void cancellationClosesDescendantsAndIgnoresLateContent() {
    RagRunTrace trace = trace();
    var parent = trace.context().start(RagStageName.ANSWER, null, 1);
    var child = parent.context().start(RagStageName.ANSWER_MODEL, null, 1);
    child.content(true, "");
    parent.stopChildren(true, "GENERATION_CANCELLED");
    parent.cancelled("GENERATION_CANCELLED");
    child.content(false, "late");
    child.success(1);
    var snapshot = trace.finish(RagRunStatus.CANCELLED, "GENERATION_CANCELLED");
    assertTrue(snapshot.stages().stream().allMatch(s -> s.status() == RagStageStatus.CANCELLED));
    assertTrue(snapshot.stages().stream().allMatch(s -> s.firstAnswerMs() == null));
  }

  @Test
  void recordsDistinctFirstContentAndZeroExecutionForQueuedCancellation() {
    RagRunTrace trace = trace();
    var queued =
        trace
            .context()
            .start(RagStageName.SUBQUESTION_EXECUTION, "Q1", 1)
            .queued(System.nanoTime() - 10_000_000);
    queued.cancelledBeforeStart("GENERATION_CANCELLED");
    var model = trace.context().start(RagStageName.ANSWER_MODEL, null, 1);
    model.content(false, "answer");
    model.content(true, "reasoning");
    trace.deltaSent(false, "answer");
    trace.deltaSent(true, "reasoning");
    model.success(1);
    trace.finalAnswer(model, "id", "provider", "model");
    var snapshot = trace.finish(RagRunStatus.COMPLETED, null);
    assertEquals(0, snapshot.stages().getFirst().elapsedMs());
    assertTrue(snapshot.stages().getFirst().queueMs() >= 0);
    assertNotNull(snapshot.firstAnswerMs());
    assertNotNull(snapshot.firstReasoningMs());
    assertEquals(snapshot.firstAnswerMs(), snapshot.endToEndTtftMs());
    assertEquals(snapshot.stages().getLast().firstAnswerMs(), snapshot.modelTtftMs());
  }

  @Test
  void recordsConcurrentStagesWithUniqueSequenceNumbers() throws Exception {
    RagRunTrace trace = trace();
    try (var executor = Executors.newFixedThreadPool(8)) {
      for (int index = 0; index < 32; index++) {
        int value = index;
        executor.submit(
            () -> {
              RagRunTrace.Span span = trace.start(RagStageName.DATABASE_RETRIEVAL, "Q" + value, 1);
              span.success(value);
            });
      }
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    RagRunTrace.RunSnapshot snapshot = trace.finish(RagRunStatus.COMPLETED, null);

    assertEquals(32, snapshot.stages().size());
    assertEquals(
        32,
        new HashSet<>(snapshot.stages().stream().map(stage -> stage.sequence()).toList()).size());
  }

  @Test
  void recordsFirstSentDeltaOnceAndUsesFinalAnswerAttemptForModelTtft() {
    RagRunTrace trace = trace();
    RagRunTrace.Span failed =
        trace.start(RagStageName.ANSWER_MODEL, null, 1).model("first", "test", "first");
    failed.firstContent();
    trace.endToEndDeltaSent();
    failed.failed("MODEL_TIMEOUT");
    trace.markDegraded();
    RagRunTrace.Span answer =
        trace.start(RagStageName.ANSWER_MODEL, null, 1).model("final", "test", "final");
    answer.firstContent();
    trace.endToEndDeltaSent();
    answer.firstContent();
    trace.endToEndDeltaSent();
    answer.success(1, "PROVIDER_FALLBACK");
    trace.finalAnswer(answer, "final", "test", "final");

    RagRunTrace.RunSnapshot snapshot = trace.finish(RagRunStatus.COMPLETED, null);

    assertNotNull(snapshot.firstTokenAt());
    assertNotNull(snapshot.endToEndTtftMs());
    assertNotNull(snapshot.modelTtftMs());
    assertEquals("final", snapshot.modelId());
    assertTrue(snapshot.degraded());
  }

  @Test
  void recordsFirstContentWhenOnlyReasoningWasSent() {
    RagRunTrace trace = trace();
    RagRunTrace.Span answer =
        trace.start(RagStageName.ANSWER_MODEL, null, 1).model("reasoning", "test", "reasoning");
    answer.firstContent();
    trace.endToEndDeltaSent();
    answer.success(0);
    trace.finalAnswer(answer, "reasoning", "test", "reasoning");

    RagRunTrace.RunSnapshot snapshot = trace.finish(RagRunStatus.COMPLETED, null);

    assertNotNull(snapshot.endToEndTtftMs());
    assertNotNull(snapshot.modelTtftMs());
  }

  @Test
  void sealsOpenStagesAndKeepsTtftNullWithoutContent() {
    RagRunTrace trace = trace();
    RagRunTrace.Span open = trace.start(RagStageName.QUERY_PLANNING, null, 1);

    RagRunTrace.RunSnapshot snapshot = trace.finish(RagRunStatus.CANCELLED, "GENERATION_CANCELLED");
    open.success(1);

    assertNull(snapshot.firstTokenAt());
    assertNull(snapshot.endToEndTtftMs());
    assertNull(snapshot.modelTtftMs());
    assertEquals(1, snapshot.stages().size());
    assertEquals("GENERATION_CANCELLED", snapshot.stages().getFirst().errorCode());
  }

  @Test
  void finishesEachSpanOnceAndRejectsStagesCreatedAfterTerminalState() {
    RagRunTrace trace = trace();
    RagRunTrace.Span span = trace.start(RagStageName.QUERY_PLANNING, null, 1);
    span.success(2);
    span.failed("LATE_FAILURE");

    RagRunTrace.RunSnapshot first = trace.finish(RagRunStatus.COMPLETED, null);
    trace.start(RagStageName.RERANK, null, 2).success(1);
    RagRunTrace.RunSnapshot second = trace.finish(RagRunStatus.FAILED, "LATE_TERMINAL");

    assertSame(first, second);
    assertEquals(1, first.stages().size());
    assertEquals(RagStageStatus.SUCCESS, first.stages().getFirst().status());
    assertNull(first.stages().getFirst().errorCode());
  }

  private RagRunTrace trace() {
    return new RagRunTrace(
        UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC), System.nanoTime());
  }
}
