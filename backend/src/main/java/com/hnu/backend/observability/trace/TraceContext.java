package com.hnu.backend.observability.trace;

import com.hnu.backend.observability.RagStageName;
import java.util.UUID;
import java.util.function.Function;

/**
 * 显式传入同步阶段和异步任务的不可变父节点上下文。
 *
 * @param trace 所属运行，不能跨运行复用父节点
 * @param parentStageId 已创建的父节点；根上下文为空
 */
public record TraceContext(RagRunTrace trace, UUID parentStageId) {
  /** 开始当前父节点下的阶段。 */
  public RagRunTrace.Span start(RagStageName name, String subQuestionId, Integer inputCount) {
    return trace.start(this, name, subQuestionId, inputCount);
  }

  /** 包装同步阶段，异常原样传播，业务终态优先。 */
  public <T> T execute(
      RagStageName name,
      String subQuestionId,
      Integer inputCount,
      Function<RagRunTrace.Span, T> operation) {
    return trace.execute(this, name, subQuestionId, inputCount, operation);
  }

  /** 记录未执行的阶段及其正常跳过原因。 */
  public void skipped(RagStageName name, String subQuestionId, String reasonCode) {
    start(name, subQuestionId, 0).skipped(0, reasonCode);
  }

  /** 返回是否启用持久化采集。 */
  public boolean enabled() {
    return trace.enabled();
  }

  /** 返回日志关联使用的运行标识。 */
  public UUID runId() {
    return trace.runId();
  }

  /** 更新本次运行的候选数量。 */
  public void candidateCount(int count) {
    trace.candidateCount(count);
  }

  /** 标记当前运行发生业务降级。 */
  public void markDegraded() {
    trace.markDegraded();
  }
}
