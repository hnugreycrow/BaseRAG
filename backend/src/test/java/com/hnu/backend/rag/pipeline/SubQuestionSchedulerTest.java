package com.hnu.backend.rag.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.observability.trace.RagRunTrace;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SubQuestionSchedulerTest {
  private final ExecutorService executor = mock(ExecutorService.class);
  private final SubQuestionScheduler scheduler = new SubQuestionScheduler(executor);

  @ParameterizedTest
  @CsvSource({"10, 10, SUCCESS", "10, 11, TIMEOUT", "0, 1000, SUCCESS"})
  void completedTasksUseTheirOwnElapsedBudget(
      long budgetMs, long elapsedMs, SubQuestionExecution.Status expected) {
    var completed =
        new SubQuestionExecution(
            "Q1",
            IntentType.MCP_TOOL,
            SubQuestionExecution.Status.SUCCESS,
            List.of(),
            null,
            "DONE",
            elapsedMs);
    // 直接提供已完成 Future，验证收集结果时仍按任务自己的耗时判定边界。
    when(executor.submit(org.mockito.ArgumentMatchers.<Callable<SubQuestionExecution>>any()))
        .thenReturn(CompletableFuture.completedFuture(completed));
    var results =
        scheduler.execute(
            List.of(task("Q1", budgetMs)), CancellationToken.NONE, RagRunTrace.noop().context());
    assertEquals(expected, results.getFirst().status());
    if (expected == SubQuestionExecution.Status.SUCCESS) {
      assertSame(completed, results.getFirst());
    } else {
      assertEquals("SUBQUESTION_TIMEOUT", results.getFirst().reasonCode());
    }
  }

  @Test
  void submissionRejectionCancelsPreviouslySubmittedTasks() {
    var pending = new CompletableFuture<SubQuestionExecution>();
    var rejected = new RejectedExecutionException("executor stopped");
    when(executor.submit(org.mockito.ArgumentMatchers.<Callable<SubQuestionExecution>>any()))
        .thenReturn(pending)
        .thenThrow(rejected);
    assertSame(
        rejected,
        assertThrows(
            RejectedExecutionException.class,
            () ->
                scheduler.execute(
                    List.of(task("Q1", 0), task("Q2", 0)),
                    CancellationToken.NONE,
                    RagRunTrace.noop().context())));
    assertTrue(pending.isCancelled());
  }

  @Test
  void workerFailureCancelsOtherTasksAndPreservesOriginalException() {
    var failure = new IllegalStateException("worker failed");
    var pending = new CompletableFuture<SubQuestionExecution>();
    when(executor.submit(org.mockito.ArgumentMatchers.<Callable<SubQuestionExecution>>any()))
        .thenReturn(CompletableFuture.failedFuture(failure))
        .thenReturn(pending);
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                scheduler.execute(
                    List.of(task("Q1", 0), task("Q2", 0)),
                    CancellationToken.NONE,
                    RagRunTrace.noop().context())));
    assertTrue(pending.isCancelled());
  }

  private SubQuestionScheduler.Task task(String id, long budgetMs) {
    var route =
        new IntentRoute(
            id,
            IntentType.MCP_TOOL,
            1,
            "tool",
            Map.of(),
            RoutingReasonCode.KNOWLEDGE_SOURCE_REQUIRED);
    return new SubQuestionScheduler.Task(
        new SubQuestion(id, "问题"),
        route,
        budgetMs,
        (trace, submittedAt) -> {
          throw new AssertionError("测试执行器应返回受控 Future");
        });
  }
}
