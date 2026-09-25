package com.hnu.backend.rag.routing;

import java.util.List;

/** 与问题规划中的子问题顺序一一对应的不可变路由计划。 */
public record RoutingPlan(List<IntentRoute> routes) {
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
