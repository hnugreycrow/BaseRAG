package com.hnu.backend.conversation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.rag.model.MemoryTurn;
import com.hnu.backend.rag.model.QueryPlan;
import com.hnu.backend.rag.model.RagMemory;
import com.hnu.backend.rag.pipeline.stage.MemoryStage;
import com.hnu.backend.rag.pipeline.stage.QueryPlanningStage;
import com.hnu.backend.rag.port.MemoryProvider;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationContextServiceTest {
  private final MemoryProvider memories = mock(MemoryProvider.class);
  private final QueryPlanningStage planning = mock(QueryPlanningStage.class);
  private final ConversationContextService service =
      new ConversationContextService(new MemoryStage(memories), planning);

  @Test
  void formatsRagMemoryAndReturnsStructuredPlan() {
    Conversation conversation = conversation();
    RagMemory memory =
        new RagMemory(
            "{\"goalsAndTopics\":[\"用户称：制度\"]}",
            2,
            List.of(new MemoryTurn(4, "较早问题", "较早回答")),
            List.of(new MemoryTurn(5, "最近问题", "最近回答")),
            5);
    QueryPlan plan = QueryPlan.fallback("制度有什么要求？");
    when(memories.load(conversation.getId(), 6)).thenReturn(memory);
    when(planning.execute(memory, "它有什么要求？")).thenReturn(plan);

    var prepared = service.prepare(conversation, 6, "它有什么要求？");

    assertEquals(plan, prepared.queryPlan());
    assertTrue(prepared.history().contains("用户称：制度"));
    assertTrue(prepared.history().contains("[轮次 4]"));
    assertTrue(prepared.history().contains("[轮次 5]"));
    verify(memories).load(conversation.getId(), 6);
    verify(planning).execute(memory, "它有什么要求？");
  }

  @Test
  void plansFirstQuestionWithoutCompletedTurns() {
    Conversation conversation = conversation();
    RagMemory memory = new RagMemory("{}", 0, List.of(), List.of(), 0);
    QueryPlan plan = QueryPlan.fallback("原问题");
    when(memories.load(conversation.getId(), 1)).thenReturn(memory);
    when(planning.execute(memory, "原问题")).thenReturn(plan);

    var prepared = service.prepare(conversation, 1, "原问题");

    assertEquals(plan, prepared.queryPlan());
    assertFalse(prepared.history().contains("[轮次"));
    verify(planning).execute(memory, "原问题");
  }

  private Conversation conversation() {
    Conversation value = new Conversation();
    value.setId(UUID.randomUUID());
    return value;
  }
}
