package com.hnu.backend.observability.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.entity.UserRole;
import com.hnu.backend.auth.service.CurrentUserService;
import com.hnu.backend.observability.RagExecutionMode;
import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.service.RagRunQueryService;
import com.hnu.backend.observability.vo.RagRunResponses;
import com.hnu.backend.shared.error.GlobalExceptionHandler;
import com.hnu.backend.shared.web.ApiResponseAdvice;
import com.hnu.backend.shared.web.PageResponse;
import com.hnu.backend.shared.web.RequestIdFilter;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RagRunControllerTest {
  private final CurrentUserService currentUserService = mock(CurrentUserService.class);
  private final RagRunQueryService ragRunQueryService = mock(RagRunQueryService.class);
  private final User actor = user();
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    when(currentUserService.require()).thenReturn(actor);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new RagRunController(currentUserService, ragRunQueryService))
            .setControllerAdvice(new ApiResponseAdvice(), new GlobalExceptionHandler())
            .addFilters(new RequestIdFilter())
            .build();
  }

  @Test
  void bindsAllListFiltersAndOneBasedPagination() throws Exception {
    OffsetDateTime from = OffsetDateTime.parse("2026-09-01T00:00:00Z");
    OffsetDateTime to = OffsetDateTime.parse("2026-10-01T00:00:00Z");
    UUID userId = UUID.randomUUID();
    when(ragRunQueryService.list(
            actor,
            from,
            to,
            RagRunStatus.COMPLETED,
            "answer-model",
            RagExecutionMode.FULL_PIPELINE,
            userId,
            2,
            10))
        .thenReturn(PageResponse.empty(2, 10));

    mvc.perform(
            get("/api/observability/rag-runs")
                .param("from", from.toString())
                .param("to", to.toString())
                .param("status", "COMPLETED")
                .param("model", "answer-model")
                .param("executionMode", "FULL_PIPELINE")
                .param("userId", userId.toString())
                .param("page", "2")
                .param("pageSize", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page").value(2))
        .andExpect(jsonPath("$.data.pageSize").value(10));

    verify(ragRunQueryService)
        .list(
            actor,
            from,
            to,
            RagRunStatus.COMPLETED,
            "answer-model",
            RagExecutionMode.FULL_PIPELINE,
            userId,
            2,
            10);
  }

  @Test
  void routesStaticSummaryPathBeforeUuidDetailPath() throws Exception {
    RagRunResponses.Aggregate aggregate =
        new RagRunResponses.Aggregate(
            0,
            0,
            0,
            new RagRunResponses.Percentiles(null, null),
            new RagRunResponses.Percentiles(null, null),
            new RagRunResponses.Percentiles(null, null));
    when(ragRunQueryService.summary(actor, null, null, null, null, null, null))
        .thenReturn(aggregate);

    mvc.perform(get("/api/observability/rag-runs/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.requestCount").value(0));

    verify(ragRunQueryService).summary(actor, null, null, null, null, null, null);
  }

  @Test
  void omitsAdministratorOnlyUserFieldsWhenTheyAreNotAuthorized() throws Exception {
    UUID runId = UUID.randomUUID();
    RagRunResponses.Summary summary =
        new RagRunResponses.Summary(
            runId,
            null,
            null,
            null,
            "safe-request-id",
            null,
            null,
            null,
            "如何使用知识库？",
            RagRunStatus.COMPLETED,
            RagExecutionMode.SYSTEM_CHAT,
            null,
            null,
            null,
            0,
            0,
            false,
            null,
            OffsetDateTime.parse("2026-09-01T00:00:00Z"),
            null,
            null,
            null,
            null,
            null);
    when(ragRunQueryService.get(actor, runId))
        .thenReturn(new RagRunResponses.Detail(summary, List.of(), List.of()));

    mvc.perform(get("/api/observability/rag-runs/{id}", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.run.requestId").value("safe-request-id"))
        .andExpect(jsonPath("$.data.run.ownerId").doesNotExist())
        .andExpect(jsonPath("$.data.run.username").doesNotExist())
        .andExpect(jsonPath("$.data.run.displayName").doesNotExist())
        .andExpect(jsonPath("$.data.run.question").value("如何使用知识库？"))
        .andExpect(jsonPath("$.data.run.content").doesNotExist())
        .andExpect(jsonPath("$.data.run.prompt").doesNotExist());
  }

  private User user() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setRole(UserRole.ADMIN);
    user.setEnabled(true);
    return user;
  }
}
