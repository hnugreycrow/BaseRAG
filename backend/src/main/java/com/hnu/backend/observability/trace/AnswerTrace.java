package com.hnu.backend.observability.trace;

/** 回答传输与终态所需的最小观测契约。 */
public interface AnswerTrace {
  /** 记录已经成功发出的正文或思考增量。 */
  void sent(boolean reasoning, String text);

  /** 返回最终模型阶段；没有调用模型时为空。 */
  RagRunTrace.Span finalModelSpan();
}
