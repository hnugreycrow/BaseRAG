package com.hnu.backend.rag.pipeline;

import com.hnu.backend.common.exception.ApiException;

/** 跨并发检索任务传播用户取消状态的轻量令牌。 */
@FunctionalInterface
public interface CancellationToken {
  /** 始终未取消的令牌，供不需要外部取消的调用使用。 */
  CancellationToken NONE = () -> false;

  /**
   * 读取共享取消状态；实现应允许并发任务安全调用。
   *
   * @return 已请求取消时为 {@code true}
   */
  boolean cancelled();

  /** 在外部取消或工作线程被中断时统一抛出既有的生成取消异常。 */
  default void throwIfCancelled() {
    if (cancelled() || Thread.currentThread().isInterrupted()) {
      throw ApiException.cancelled();
    }
  }
}
