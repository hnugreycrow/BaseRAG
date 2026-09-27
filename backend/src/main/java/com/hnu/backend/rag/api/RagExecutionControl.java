package com.hnu.backend.rag.api;

import com.hnu.backend.common.exception.ApiException;

/** 单次执行的取消检查与模型流释放契约。 */
public interface RagExecutionControl extends AutoCloseable {
  /**
   * 判断模型流是否已被取消。
   *
   * @return 已取消时为 true
   */
  boolean cancelled();

  /**
   * 在已取消或当前工作线程被中断时抛出统一取消异常。
   *
   * @throws ApiException 请求已取消时抛出
   */
  default void throwIfCancelled() {
    if (cancelled() || Thread.currentThread().isInterrupted()) {
      throw ApiException.cancelled();
    }
  }

  /** 主动关闭底层响应流并把控制器标记为已取消。 */
  @Override
  void close();
}
