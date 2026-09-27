package com.hnu.backend.rag.api;

import com.hnu.backend.rag.pipeline.QueryPlan;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * KB 消歧的可恢复上下文，不包含工具参数或执行结果。
 *
 * @param originalMessageId 最初用户消息标识
 * @param plan 首次规划结果，续接时保持子问题标识稳定
 * @param selections 已确认的子问题到叶子映射
 * @param pending 按规划顺序等待选择的歧义
 * @param supplementIds 已接受的补充消息引用
 * @param totalSteps 本轮最初需要澄清的子问题总数，后续续接保持不变
 */
public record ClarificationContext(
    UUID originalMessageId,
    QueryPlan plan,
    Map<String, UUID> selections,
    List<Ambiguity> pending,
    List<UUID> supplementIds,
    Integer totalSteps) {
  /** 固定上下文集合，防止异步生成修改持久化快照。 */
  public ClarificationContext {
    selections = Map.copyOf(selections);
    pending = List.copyOf(pending);
    supplementIds = List.copyOf(supplementIds);
    totalSteps = Math.max(totalSteps == null ? 0 : totalSteps, pending.size());
  }

  /** 兼容未记录总项数的上下文；以当前待选数量作为起始进度。 */
  public ClarificationContext(
      UUID originalMessageId,
      QueryPlan plan,
      Map<String, UUID> selections,
      List<Ambiguity> pending,
      List<UUID> supplementIds) {
    this(originalMessageId, plan, selections, pending, supplementIds, pending.size());
  }

  /**
   * 一个子问题的待选意图。
   *
   * @param subQuestionId 稳定子问题标识
   * @param options 服务端允许的候选
   */
  public record Ambiguity(String subQuestionId, List<Option> options) {
    /** 固定候选快照。 */
    public Ambiguity {
      options = List.copyOf(options);
    }
  }

  /**
   * 对外可展示的意图选项。
   *
   * @param nodeId KB 叶子标识
   * @param label 完整意图路径
   * @param description 管理员配置的意图说明，旧快照为空字符串
   */
  public record Option(UUID nodeId, String label, String description) {
    /** 兼容旧快照中缺省的意图说明。 */
    public Option {
      description = description == null ? "" : description;
    }

    /** 兼容只含路径的选项构造。 */
    public Option(UUID nodeId, String label) {
      this(nodeId, label, "");
    }
  }
}
