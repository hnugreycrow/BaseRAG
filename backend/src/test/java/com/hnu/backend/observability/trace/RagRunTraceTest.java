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
    failed.failed("MODEL_TIMEOUT");
    trace.markDegraded();
    RagRunTrace.Span answer =
        trace.start(RagStageName.ANSWER_MODEL, null, 1).model("final", "test", "final");
    answer.firstContent();
    trace.endToEndDeltaSent();
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
