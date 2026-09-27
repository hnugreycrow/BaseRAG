package com.hnu.backend.rag.pipeline;

import java.util.List;

/**
 * 与问题规划中的子问题顺序一一对应的不可变路由计划。
 *
 * @param routes 非空路由列表，构造时复制以固定本轮执行顺序
 */
public record RoutingPlan(List<IntentRoute> routes) {
  /** 拒绝空计划，并固定路由列表以保留查询规划顺序。 */
  public RoutingPlan {
    routes = List.copyOf(routes);
    if (routes.isEmpty()) {
      throw new IllegalArgumentException("routes must not be empty");
    }
  }

  /** 仅当所有子问题都是闲聊时才允许跳过知识检索。 */
  public boolean systemChatOnly() {
    return routes.stream().allMatch(route -> route.intent() == IntentType.SYSTEM_CHAT);
  }
}
