package com.hnu.backend.rag.pipeline;

import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.observability.trace.TraceContext;
import java.util.List;
import java.util.UUID;

/** 在预算和取消约束内调度子问题，并生成候选执行结果。 */
public interface QueryExecution {
  /**
   * 按路由执行全部子问题，默认不限制知识库范围且不提供外部取消信号。
   *
   * @param ownerId 所属用户标识
   * @param plan 查询计划
   * @param routing 与查询计划对齐的路由计划
   * @return 执行结果
   */
  ExecutionResult execute(UUID ownerId, QueryPlan plan, RoutingPlan routing);

  /**
   * 在用户所有权范围内并发执行检索或工具子问题。
   *
   * @param ownerId 所属用户标识；会被显式传入异步检索任务
   * @param plan 查询计划
   * @param routing 路由计划
   * @param knowledgeBaseIds 可选知识库范围
   * @param cancellationToken 取消信号
   * @return 聚合后的执行结果
   */
  ExecutionResult execute(
      UUID ownerId,
      QueryPlan plan,
      RoutingPlan routing,
      List<UUID> knowledgeBaseIds,
      CancellationToken cancellationToken);

  /**
   * 并发执行子问题并显式向子线程传播 Trace。
   *
   * @param ownerId 所属用户标识
   * @param plan 查询计划
   * @param routing 路由计划
   * @param knowledgeBaseIds 可选知识库范围
   * @param cancellationToken 取消信号
   * @param trace 当前问答 Trace
   * @return 聚合后的执行结果
   */
  ExecutionResult execute(
      UUID ownerId,
      QueryPlan plan,
      RoutingPlan routing,
      List<UUID> knowledgeBaseIds,
      CancellationToken cancellationToken,
      RagRunTrace trace);

  /** 执行检索范围；候选合并由调用方的证据整理阶段负责。 */
  ExecutionResult executeRetrieval(
      UUID ownerId,
      QueryPlan plan,
      RoutingPlan routing,
      List<UUID> knowledgeBaseIds,
      CancellationToken cancellationToken,
      TraceContext trace);

  /** 在证据整理父节点下合并候选，保留原来的召回数量与排序规则。 */
  ExecutionResult merge(ExecutionResult execution, TraceContext trace);
}
