package com.hnu.backend.observability.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hnu.backend.observability.entity.RagRun;
import com.hnu.backend.observability.mapper.RagRunMapper;
import com.hnu.backend.observability.mapper.RagStageRunMapper;
import com.hnu.backend.shared.web.RequestTiming;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RagTraceManagerTest {
  @Test
  void storesQuestionSnapshotWhenStartingRun() {
    RagRunMapper ragRunMapper = mock(RagRunMapper.class);
    RagStageRunMapper ragStageRunMapper = mock(RagStageRunMapper.class);
    RagTraceManager manager = new RagTraceManager(ragRunMapper, ragStageRunMapper);
    UUID ownerId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID userMessageId = UUID.randomUUID();
    UUID assistantMessageId = UUID.randomUUID();
    RequestTiming timing =
        new RequestTiming(
            "request-1", OffsetDateTime.parse("2026-09-19T08:00:00Z"), System.nanoTime());

    var trace =
        manager.start(
            ownerId, conversationId, userMessageId, assistantMessageId, "如何使用知识库？", timing);

    assertNotNull(trace.runId());
    verify(ragRunMapper)
        .insert(
            argThat(
                (RagRun run) ->
                    ownerId.equals(run.getOwnerId())
                        && conversationId.equals(run.getConversationId())
                        && userMessageId.equals(run.getUserMessageId())
                        && assistantMessageId.equals(run.getAssistantMessageId())
                        && "如何使用知识库？".equals(run.getQuestion())
                        && timing.startedAt().equals(run.getStartedAt())));
  }
}
