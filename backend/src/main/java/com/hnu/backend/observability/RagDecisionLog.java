package com.hnu.backend.observability;

/** 隔离决策日志故障，避免日志格式化或写入改变问答结果。 */
public final class RagDecisionLog {
  private RagDecisionLog() {}

  /** 将日志值截断到 2000 字符并转义控制字符，避免用户内容伪造日志行。 */
  public static String value(String value) {
    if (value == null) {
      return "null";
    }
    String bounded = value.length() > 2000 ? value.substring(0, 2000) + "…[truncated]" : value;
    return com.hnu.backend.common.json.JsonCodecs.models().writeValueAsString(bounded);
  }

  /** 仅执行日志操作；日志异常不传播到规划或路由阶段。 */
  public static void emit(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException ignored) {
      // 日志是旁路观测，不能触发规划降级或路由回退。
    }
  }
}
