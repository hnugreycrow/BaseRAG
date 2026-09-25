package com.hnu.backend.rag.execution;

import com.hnu.backend.shared.error.ApiException;

/** 跨并发检索任务传播用户取消状态的轻量令牌。 */
@FunctionalInterface
public interface CancellationToken {
  CancellationToken NONE = () -> false;

  boolean cancelled();

  /** 在外部取消或工作线程被中断时统一抛出既有的生成取消异常。 */
  default void throwIfCancelled() {
    if (cancelled() || Thread.currentThread().isInterrupted()) {
      throw ApiException.cancelled();
    }
  }
}
