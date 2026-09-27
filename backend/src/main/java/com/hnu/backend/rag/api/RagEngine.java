package com.hnu.backend.rag.api;

import com.hnu.backend.observability.trace.RagRunTrace;

/** 与会话、HTTP 和持久化无关的问答入口。 */
public interface RagEngine {
  /** 创建独占控制器；调用方负责最终关闭。 */
  RagExecutionControl newControl();

  /**
   * 执行请求，异常原样传播，由调用方持久化终态。
   *
   * @param request 本次不可变输入，执行模式决定兼容路径
   * @param observer 同步事件接收器；回调失败会终止执行
   * @param control 由此引擎创建、本次请求独占的控制器；调用方最终关闭
   * @param trace 当前运行追踪；无需采集时使用 {@link RagRunTrace#noop()}
   * @return 最终答案及已验证的来源、引用和模型信息
   */
  RagResult execute(
      RagRequest request, RagObserver observer, RagExecutionControl control, RagRunTrace trace);
}
