package com.hnu.backend.conversation.service;

import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.rag.memory.MemoryStage;
import com.hnu.backend.rag.memory.RagMemory;
import com.hnu.backend.rag.planning.QueryPlan;
import com.hnu.backend.rag.planning.QueryPlanningStage;
import com.hnu.backend.rag.routing.IntentRoutingStage;
import com.hnu.backend.rag.routing.RoutingPlan;
import org.springframework.stereotype.Service;

/** 依次加载会话记忆、生成问题规划并完成意图路由。 */
@Service
public class ConversationContextService {
  private final MemoryStage memoryStage;
  private final QueryPlanningStage queryPlanningStage;
  private final IntentRoutingStage intentRoutingStage;

  /**
   * 创建会话上下文准备服务。
   *
   * @param memoryStage 会话记忆阶段
   * @param queryPlanningStage 问题规划阶段
   * @param intentRoutingStage 意图路由阶段
   */
  public ConversationContextService(
      MemoryStage memoryStage,
      QueryPlanningStage queryPlanningStage,
      IntentRoutingStage intentRoutingStage) {
    this.memoryStage = memoryStage;
    this.queryPlanningStage = queryPlanningStage;
    this.intentRoutingStage = intentRoutingStage;
  }

  /**
   * 为一个新用户轮次准备后续执行和提示词组装所需的中立上下文。
   *
   * @param conversation 当前会话
   * @param currentTurn 当前用户轮次
   * @param question 用户原始问题
   * @return 原始记忆、规划和安全路由的不可变组合
   * @throws IllegalArgumentException 会话所有者或记忆输入无效时抛出
   */
  public PreparedContext prepare(Conversation conversation, int currentTurn, String question) {
    RagMemory memory =
        memoryStage.execute(conversation.getOwnerId(), conversation.getId(), currentTurn);
    QueryPlan queryPlan = queryPlanningStage.execute(memory, question);
    RoutingPlan routingPlan = intentRoutingStage.execute(queryPlan);
    return new PreparedContext(memory, queryPlan, routingPlan);
  }

  /**
   * 会话回答流水线前三个阶段的组合结果。
   *
   * @param memory 未格式化的会话记忆，只用于理解上下文
   * @param queryPlan 已校验的问题改写与子问题计划
   * @param routingPlan 与子问题顺序对齐的安全路由计划
   */
  public record PreparedContext(RagMemory memory, QueryPlan queryPlan, RoutingPlan routingPlan) {}
}
