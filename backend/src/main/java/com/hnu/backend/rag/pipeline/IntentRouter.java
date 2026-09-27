package com.hnu.backend.rag.pipeline;

import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.observability.trace.TraceContext;

/** 将查询计划映射为按子问题顺序排列的安全路由。 */
public interface IntentRouter {
  /** 读取树快照并分类；单个子问题无效时只将该子问题降级到全公共库检索。 */
  RoutingPlan execute(QueryPlan plan, TraceContext trace);

  /** 兼容根 Trace 入口；内部显式传递父节点上下文。 */
  RoutingPlan execute(QueryPlan plan, RagRunTrace trace);
}
