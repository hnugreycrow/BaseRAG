package com.hnu.backend.conversation.generation;

import com.hnu.backend.conversation.vo.ConversationStreamEvents.Kind;
import com.hnu.backend.shared.error.ApiException;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 串行发送会话 SSE 事件并管理保活任务。 */
final class ConversationSseChannel {
  private static final int HEARTBEAT_INTERVAL_SECONDS = 15;
  private final SseEmitter emitter = new SseEmitter(0L);
  private Future<?> heartbeat;

  SseEmitter emitter() {
    return emitter;
  }

  void send(Kind name, Object data) {
    try {
      synchronized (emitter) {
        emitter.send(SseEmitter.event().name(name.wireName()).data(data));
      }
    } catch (IOException | IllegalStateException error) {
      throw ApiException.cancelled();
    }
  }

  void startHeartbeat(ExecutorService executor, BooleanSupplier terminal, Runnable disconnect) {
    heartbeat =
        executor.submit(
            () -> {
              try {
                while (!terminal.getAsBoolean()) {
                  TimeUnit.SECONDS.sleep(HEARTBEAT_INTERVAL_SECONDS);
                  synchronized (emitter) {
                    if (terminal.getAsBoolean()) {
                      return;
                    }
                    emitter.send(SseEmitter.event().comment("ping"));
                  }
                }
              } catch (InterruptedException ignored) {
                // 正常完成或取消时主动结束保活任务。
                Thread.currentThread().interrupt();
              } catch (IOException | IllegalStateException error) {
                disconnect.run();
              }
            });
  }

  void finish(Kind event, Object payload) {
    Future<?> activeHeartbeat = heartbeat;
    if (activeHeartbeat != null) {
      activeHeartbeat.cancel(true);
    }
    try {
      send(event, payload);
      emitter.complete();
    } catch (RuntimeException error) {
      emitter.completeWithError(error);
    }
  }

  void completeWithError(RuntimeException error) {
    emitter.completeWithError(error);
  }
}
