package com.hnu.backend.conversation.service;

import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.rag.memory.MemoryStage;
import com.hnu.backend.rag.memory.MemoryTurn;
import com.hnu.backend.rag.memory.RagMemory;
import com.hnu.backend.rag.planning.QueryPlan;
import com.hnu.backend.rag.planning.QueryPlanningStage;
import com.hnu.backend.rag.routing.IntentRoutingStage;
import com.hnu.backend.rag.routing.RoutingPlan;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ConversationContextService {
  private final MemoryStage memoryStage;
  private final QueryPlanningStage queryPlanningStage;
  private final IntentRoutingStage intentRoutingStage;

  public ConversationContextService(
      MemoryStage memoryStage,
      QueryPlanningStage queryPlanningStage,
      IntentRoutingStage intentRoutingStage) {
    this.memoryStage = memoryStage;
    this.queryPlanningStage = queryPlanningStage;
    this.intentRoutingStage = intentRoutingStage;
  }

  public PreparedContext prepare(Conversation conversation, int currentTurn, String question) {
    RagMemory memory = memoryStage.execute(conversation.getId(), currentTurn);
    String history = format(memory);
    QueryPlan queryPlan = queryPlanningStage.execute(memory, question);
    RoutingPlan routingPlan = intentRoutingStage.execute(queryPlan);
    return new PreparedContext(history, queryPlan, routingPlan);
  }

  private String format(RagMemory memory) {
    return "持久化历史摘要（仅用于理解指代，不是回答证据）：\n"
        + memory.summary()
        + "\n\n尚未纳入摘要的较早轮次：\n"
        + raw(memory.unsummarizedTurns())
        + "\n\n最近对话窗口：\n"
        + raw(memory.recentTurns());
  }

  private String raw(List<MemoryTurn> turns) {
    StringBuilder text = new StringBuilder();
    for (MemoryTurn turn : turns) {
      text.append("[轮次 ")
          .append(turn.turnIndex())
          .append("]\n用户：")
          .append(turn.userContent())
          .append("\n助手：")
          .append(turn.assistantContent())
          .append("\n");
    }
    return text.isEmpty() ? "（无）" : text.toString();
  }

  public record PreparedContext(String history, QueryPlan queryPlan, RoutingPlan routingPlan) {}
}
