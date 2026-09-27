package com.hnu.backend.rag.generation;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.rag.api.RagExecutionControl;
import com.hnu.backend.rag.api.RagObserver;

/** 编排回答模型和引用策略，输出可持久化的最终回答。 */
public interface AnswerGeneration {
  /**
   * 创建一次回答请求独占的可取消流控制器。
   *
   * @return 回答模型流控制器
   */
  RagExecutionControl newControl();

  /**
   * 根据已组装提示词生成最终回答，并在引用非法时最多完整修复一次。
   *
   * @param prompt 阶段六产出的不可变提示词快照
   * @param observer 回答生命周期观察器
   * @param control 本次请求独占的流控制器
   * @return 可直接持久化的回答结果
   * @throws ApiException 请求取消、模型失败或连续两次引用非法时抛出
   */
  AnswerResult execute(AssembledPrompt prompt, RagObserver observer, RagExecutionControl control);

  /** 根据本回答版本固定的思考选择执行回答生成。 */
  AnswerResult execute(
      AssembledPrompt prompt,
      RagObserver observer,
      RagExecutionControl control,
      boolean thinkingEnabled);
}
