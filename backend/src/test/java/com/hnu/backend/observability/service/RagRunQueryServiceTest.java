package com.hnu.backend.observability.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.entity.UserRole;
import com.hnu.backend.observability.RagExecutionMode;
import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.RagStageStatus;
import com.hnu.backend.observability.entity.RagStageRun;
import com.hnu.backend.observability.mapper.RagRunMapper;
import com.hnu.backend.observability.mapper.RagRunSummaryRow;
import com.hnu.backend.observability.mapper.RagRunViewRow;
import com.hnu.backend.observability.mapper.RagStageRunMapper;
import com.hnu.backend.shared.error.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RagRunQueryServiceTest {
  private final RagRunMapper ragRunMapper = mock(RagRunMapper.class);
  private final RagStageRunMapper ragStageRunMapper = mock(RagStageRunMapper.class);
  private final RagRunQueryService ragRunQueryService =
      new RagRunQueryService(ragRunMapper, ragStageRunMapper);

  @Test
  void rejectsOtherUserFilterForRegularUser() {
    User actor = user(UserRole.USER);

    ApiException error =
        assertThrows(
            ApiException.class,
            () ->
                ragRunQueryService.list(
                    actor, null, null, null, null, null, UUID.randomUUID(), 1, 20));

    assertEquals("FORBIDDEN", error.code());
    verifyNoInteractions(ragRunMapper, ragStageRunMapper);
  }

  @Test
  void scopesRegularUserAndAllowsAdministratorGlobalSummary() {
    User regular = user(UserRole.USER);
    when(ragRunMapper.count(argThat(filter -> regular.getId().equals(filter.ownerId()))))
        .thenReturn(0L);
    when(ragRunMapper.list(any(), eq(20), eq(0L))).thenReturn(List.of());

    assertEquals(
        0,
        ragRunQueryService.list(regular, null, null, null, null, null, null, 1, 20).items().size());

    RagRunSummaryRow row = new RagRunSummaryRow();
    row.setRequestCount(5);
    row.setTerminalCount(4);
    row.setSuccessCount(3);
    row.setDegradedCount(1);
    when(ragRunMapper.summary(argThat(filter -> filter.ownerId() == null))).thenReturn(row);

    var aggregate =
        ragRunQueryService.summary(user(UserRole.ADMIN), null, null, null, null, null, null);
    assertEquals(5, aggregate.requestCount());
    assertEquals(0.75, aggregate.successRate());
    assertEquals(0.25, aggregate.degradedRate());
  }

  @Test
  void hidesUserFieldsFromRegularResponsesAndIncludesThemForAdministrator() {
    User regular = user(UserRole.USER);
    RagRunViewRow row = run(regular.getId());
    when(ragRunMapper.count(any())).thenReturn(1L);
    when(ragRunMapper.list(any(), eq(20), eq(0L))).thenReturn(List.of(row));

    var regularSummary =
        ragRunQueryService
            .list(regular, null, null, null, null, null, null, 1, 20)
            .items()
            .getFirst();
    assertNull(regularSummary.ownerId());
    assertNull(regularSummary.username());
    assertNull(regularSummary.displayName());

    var adminSummary =
        ragRunQueryService
            .list(user(UserRole.ADMIN), null, null, null, null, null, null, 1, 20)
            .items()
            .getFirst();
    assertEquals(row.getOwnerId(), adminSummary.ownerId());
    assertEquals("alice", adminSummary.username());
    assertEquals("Alice", adminSummary.displayName());
    assertEquals("如何使用知识库？", adminSummary.question());
  }

  @Test
  void hidesCrossUserDetailAsNotFound() {
    User regular = user(UserRole.USER);
    UUID runId = UUID.randomUUID();
    when(ragRunMapper.findView(runId, regular.getId())).thenReturn(null);

    ApiException error =
        assertThrows(ApiException.class, () -> ragRunQueryService.get(regular, runId));

    assertEquals("RAG_RUN_NOT_FOUND", error.code());
    assertEquals(404, error.status().value());
    verifyNoInteractions(ragStageRunMapper);
  }

  @Test
  void returnsDeduplicatedDegradationReasonsIncludingAnswerFallback() {
    User admin = user(UserRole.ADMIN);
    RagRunViewRow row = run(UUID.randomUUID());
    RagStageRun fallback =
        stage(RagStageName.ANSWER_MODEL, RagStageStatus.SUCCESS, "PROVIDER_FALLBACK");
    RagStageRun duplicate =
        stage(RagStageName.ANSWER_MODEL, RagStageStatus.SUCCESS, "PROVIDER_FALLBACK");
    RagStageRun summary =
        stage(RagStageName.MEMORY_SUMMARY, RagStageStatus.DEGRADED, "MODEL_TIMEOUT");
    when(ragRunMapper.findView(row.getId(), null)).thenReturn(row);
    when(ragStageRunMapper.listByRun(row.getId()))
        .thenReturn(List.of(fallback, duplicate, summary));

    var detail = ragRunQueryService.get(admin, row.getId());

    assertEquals(List.of("PROVIDER_FALLBACK", "MODEL_TIMEOUT"), detail.degradationReasons());
  }

  @Test
  void returnsZeroRatesAndNullPercentilesWithoutSamples() {
    when(ragRunMapper.summary(any())).thenReturn(new RagRunSummaryRow());

    var aggregate =
        ragRunQueryService.summary(user(UserRole.USER), null, null, null, null, null, null);

    assertEquals(0, aggregate.requestCount());
    assertEquals(0, aggregate.successRate());
    assertEquals(0, aggregate.degradedRate());
    assertNull(aggregate.totalMs().p50Ms());
    assertNull(aggregate.totalMs().p95Ms());
    assertNull(aggregate.endToEndTtftMs().p50Ms());
    assertNull(aggregate.modelTtftMs().p95Ms());
  }

  @Test
  void rejectsEmptyTimeWindowAndInvalidPage() {
    User actor = user(UserRole.USER);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    assertEquals(
        "INVALID_TIME_RANGE",
        assertThrows(
                ApiException.class,
                () -> ragRunQueryService.list(actor, now, now, null, null, null, null, 1, 20))
            .code());
    assertEquals(
        "INVALID_PAGE",
        assertThrows(
                ApiException.class,
                () -> ragRunQueryService.list(actor, null, null, null, null, null, null, 0, 20))
            .code());
  }

  private RagRunViewRow run(UUID ownerId) {
    RagRunViewRow row = new RagRunViewRow();
    row.setId(UUID.randomUUID());
    row.setOwnerId(ownerId);
    row.setUsername("alice");
    row.setDisplayName("Alice");
    row.setQuestion("如何使用知识库？");
    row.setRequestId(UUID.randomUUID().toString());
    row.setStatus(RagRunStatus.COMPLETED);
    row.setExecutionMode(RagExecutionMode.FULL_PIPELINE);
    row.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
    return row;
  }

  private RagStageRun stage(RagStageName name, RagStageStatus status, String reasonCode) {
    RagStageRun stage = new RagStageRun();
    stage.setId(UUID.randomUUID());
    stage.setStageName(name);
    stage.setStatus(status);
    stage.setReasonCode(reasonCode);
    return stage;
  }

  private User user(UserRole role) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setRole(role);
    user.setEnabled(true);
    return user;
  }
}
