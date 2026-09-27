package com.hnu.backend.rag.pipeline;

import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.observability.trace.TraceContext;
import com.hnu.backend.rag.memory.RagMemory;

/** 将原问题与记忆转换成经过校验的查询计划，并保持原有降级语义。 */
public interface QueryPlanning {
  /**
   * 生成并校验查询计划；任何模型或格式异常都会降级为原始问题。
   *
   * @param memory 当前会话记忆
   * @param originalQuestion 原始用户问题
   * @return 可安全执行的查询计划
   */
  QueryPlan execute(RagMemory memory, String originalQuestion);

  /**
   * 生成查询计划并把模型信息或降级原因写入当前 Trace。
   *
   * @param memory 当前会话记忆
   * @param originalQuestion 原始用户问题
   * @param trace 当前问答 Trace
   * @return 可安全执行的查询计划
   */
  QueryPlan execute(RagMemory memory, String originalQuestion, TraceContext trace);

  /** 兼容根 Trace 入口；内部显式传递父节点上下文。 */
  QueryPlan execute(RagMemory memory, String originalQuestion, RagRunTrace trace);
}
