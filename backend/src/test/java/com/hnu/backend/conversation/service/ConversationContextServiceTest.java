package com.hnu.backend.conversation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.rag.memory.MemoryProvider;
import com.hnu.backend.rag.memory.MemoryStage;
import com.hnu.backend.rag.memory.MemoryTurn;
import com.hnu.backend.rag.memory.RagMemory;
import com.hnu.backend.rag.pipeline.IntentRoute;
import com.hnu.backend.rag.pipeline.IntentTreeRoutingStage;
import com.hnu.backend.rag.pipeline.QueryPlan;
import com.hnu.backend.rag.pipeline.QueryPlanningStage;
import com.hnu.backend.rag.pipeline.RoutingPlan;
import com.hnu.backend.rag.pipeline.RoutingReasonCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationContextServiceTest {
  private final MemoryProvider memories = mock(MemoryProvider.class);
  private final QueryPlanningStage planning = mock(QueryPlanningStage.class);
  private final IntentTreeRoutingStage routing = mock(IntentTreeRoutingStage.class);
  private final ConversationContextService conversationContextService =
      new ConversationContextService(new MemoryStage(memories), planning, routing);

  @Test
  void returnsOriginalRagMemoryAndStructuredPlan() {
    Conversation conversation = conversation();
    RagMemory memory =
        new RagMemory(
            "用户咨询了制度要求（已讨论）",
            2,
            List.of(new MemoryTurn(4, "较早问题", "较早回答")),
            List.of(new MemoryTurn(5, "最近问题", "最近回答")),
            5);
    QueryPlan plan = QueryPlan.fallback("制度有什么要求？");
    RoutingPlan routes = knowledgeRoutes(plan);
    when(memories.load(conversation.getOwnerId(), conversation.getId(), 6)).thenReturn(memory);
    when(planning.execute(memory, "它有什么要求？")).thenReturn(plan);
    when(routing.execute(plan, RagRunTrace.noop())).thenReturn(routes);

    var prepared = conversationContextService.prepare(conversation, 6, "它有什么要求？");

    assertEquals(plan, prepared.queryPlan());
    assertEquals(routes, prepared.routingPlan());
    assertEquals(memory, prepared.memory());
    verify(memories).load(conversation.getOwnerId(), conversation.getId(), 6);
    verify(planning).execute(memory, "它有什么要求？");
    verify(routing).execute(plan, RagRunTrace.noop());
  }

  @Test
  void plansFirstQuestionWithoutCompletedTurns() {
    Conversation conversation = conversation();
    RagMemory memory = new RagMemory("", 0, List.of(), List.of(), 0);
    QueryPlan plan = QueryPlan.fallback("原问题");
    RoutingPlan routes = knowledgeRoutes(plan);
    when(memories.load(conversation.getOwnerId(), conversation.getId(), 1)).thenReturn(memory);
    when(planning.execute(memory, "原问题")).thenReturn(plan);
    when(routing.execute(plan, RagRunTrace.noop())).thenReturn(routes);

    var prepared = conversationContextService.prepare(conversation, 1, "原问题");

    assertEquals(plan, prepared.queryPlan());
    assertEquals(routes, prepared.routingPlan());
    assertEquals(memory, prepared.memory());
    verify(planning).execute(memory, "原问题");
    verify(routing).execute(plan, RagRunTrace.noop());
  }

  private RoutingPlan knowledgeRoutes(QueryPlan plan) {
    return new RoutingPlan(
        plan.subQuestions().stream()
            .map(
                question ->
                    IntentRoute.knowledgeFallback(
                        question.id(), 1, RoutingReasonCode.KNOWLEDGE_SOURCE_REQUIRED))
            .toList());
  }

  private Conversation conversation() {
    Conversation value = new Conversation();
    value.setId(UUID.randomUUID());
    value.setOwnerId(UUID.randomUUID());
    return value;
  }
}
