package com.hnu.backend.intent;

import com.hnu.backend.rag.mcp.McpToolRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 延迟加载并缓存当前单实例使用的不可变意图路由快照。 */
@Component
public class IntentTreeSnapshotProvider {
  private final IntentTreeService intentTreeService;
  private final McpToolRegistry tools;
  private final Object monitor = new Object();
  private volatile IntentTreeSnapshot cached;

  public IntentTreeSnapshotProvider(IntentTreeService intentTreeService, McpToolRegistry tools) {
    this.intentTreeService = intentTreeService;
    this.tools = tools;
  }

  /** 返回当前路由快照；缓存失效后由第一个读取线程完成重建。 */
  public IntentTreeSnapshot snapshot() {
    IntentTreeSnapshot current = cached;
    if (current != null) {
      return current;
    }
    synchronized (monitor) {
      if (cached == null) {
        cached = IntentTreeSnapshot.from(intentTreeService.list(), tools.availableReadOnlyTools());
      }
      return cached;
    }
  }

  /** 只在事务成功提交后失效；无事务发布用于已提交的编程式事务。 */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void invalidate(IntentTreeChangedEvent ignored) {
    synchronized (monitor) {
      cached = null;
    }
  }
}
