package com.hnu.backend.rag.generation;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import com.hnu.backend.observability.TraceReasonCatalog;
import java.util.List;
import org.springframework.stereotype.Component;

/** 负责阶段七最终回答生成、引用白名单校验、单次修复和取消传播。 */
@Component
public class AnswerStage {
  private static final String INSUFFICIENT_EVIDENCE = "现有资料不足以回答这个问题。请先导入包含相关内容的文档。";
  private static final ErrorCode INVALID_CITATIONS = ErrorCode.INVALID_CITATIONS;

  private final AnswerGenerator generator;
  private final PromptAssemblyStage prompts;

  /**
   * 创建最终回答阶段。
   *
   * @param generator 中立流式回答模型端口
   * @param prompts 引用修复提示词构造器
   */
  public AnswerStage(AnswerGenerator generator, PromptAssemblyStage prompts) {
    this.generator = generator;
    this.prompts = prompts;
  }

  /**
   * 创建一次回答请求独占的可取消流控制器。
   *
   * @return 回答模型流控制器
   */
  public AnswerGenerator.Control newControl() {
    return generator.newControl();
  }

  /**
   * 根据已组装提示词生成最终回答，并在引用非法时最多完整修复一次。
   *
   * @param prompt 阶段六产出的不可变提示词快照
   * @param observer 回答生命周期观察器
   * @param control 本次请求独占的流控制器
   * @return 可直接持久化的回答结果
   * @throws ApiException 请求取消、模型失败或连续两次引用非法时抛出
   */
  public AnswerResult execute(
      AssembledPrompt prompt, Observer observer, AnswerGenerator.Control control) {
    return execute(prompt, observer, control, false);
  }

  /** 根据本回答版本固定的思考选择执行回答生成。 */
  public AnswerResult execute(
      AssembledPrompt prompt,
      Observer observer,
      AnswerGenerator.Control control,
      boolean thinkingEnabled) {
    control.throwIfCancelled();
    if (!prompt.shouldGenerate()) {
      observer.generationSkipped(TraceReasonCatalog.NO_EVIDENCE.code());
      return new AnswerResult(INSUFFICIENT_EVIDENCE, prompt.sources(), List.of(), List.of(), null);
    }
    AnswerGenerator.Generation generation =
        generate(prompt, AnswerGenerator.AttemptReason.PRIMARY, observer, control, thinkingEnabled);
    Citations.Validation references;
    try {
      observer.validationStarted();
      references = validate(generation, prompt);
      observer.validationCompleted(references.citations().size());
    } catch (IllegalArgumentException invalid) {
      // 首次非法回答已经完成模型流，先作废尝试并清空客户端正文，再启动唯一一次修复。
      observer.invalidReferences(INVALID_CITATIONS.code(), true);
      control.throwIfCancelled();
      AssembledPrompt repair = prompts.forCitationRepair(prompt);
      generation =
          generate(
              repair,
              AnswerGenerator.AttemptReason.CITATION_REPAIR,
              observer,
              control,
              thinkingEnabled);
      try {
        observer.validationStarted();
        references = validate(generation, repair);
        observer.validationCompleted(references.citations().size());
      } catch (IllegalArgumentException again) {
        observer.invalidReferences(INVALID_CITATIONS.code(), false);
        throw ApiException.upstream(INVALID_CITATIONS, "模型连续返回无效引用，请重试");
      }
    }
    control.throwIfCancelled();
    String normalized = Citations.normalize(generation.content());
    if (!normalized.equals(generation.content())) {
      observer.normalizedAnswer(normalized);
    }
    control.throwIfCancelled();
    return new AnswerResult(
        normalized,
        prompt.sources(),
        references.citations(),
        references.toolReferences(),
        generation);
  }

  /**
   * 返回无需处理流式事件的观察器，供同步兼容入口复用回答阶段。
   *
   * @return 无副作用观察器
   */
  public static Observer noopObserver() {
    return NoopObserver.INSTANCE;
  }

  /**
   * 调用模型端口并在调用边界检查取消状态。
   *
   * @param prompt 当前生成使用的提示词
   * @param reason 当前生成原因
   * @param observer 回答生命周期观察器
   * @param control 模型流控制器
   * @return 完整模型生成结果
   */
  private AnswerGenerator.Generation generate(
      AssembledPrompt prompt,
      AnswerGenerator.AttemptReason reason,
      Observer observer,
      AnswerGenerator.Control control,
      boolean thinkingEnabled) {
    control.throwIfCancelled();
    AnswerGenerator.Generation generation =
        thinkingEnabled
            ? generator.generate(
                new AnswerGenerator.Request(prompt.systemPrompt(), prompt.userPrompt(), true),
                reason,
                observer,
                control)
            : generator.generate(
                prompt.systemPrompt(), prompt.userPrompt(), reason, observer, control);
    control.throwIfCancelled();
    return generation;
  }

  /**
   * 分别校验知识引用和工具编号，禁止模型引用本轮提示词之外的数据。
   *
   * @param generation 完整模型回答
   * @param prompt 本次回答实际使用的提示词快照
   * @return 按正文首次出现顺序排列的合法引用
   */
  private Citations.Validation validate(
      AnswerGenerator.Generation generation, AssembledPrompt prompt) {
    return Citations.validate(generation.content(), prompt.sources(), prompt.toolReferenceIds());
  }

  /** 在模型流事件之外接收引用校验失败通知。 */
  public interface Observer extends AnswerGenerator.StreamObserver {
    /** 回答引用位置改变时通知流式调用方重置并发送规范化后的完整正文。 */
    default void normalizedAnswer(String content) {}

    /** 通知调用方本轮没有证据且未调用最终回答模型。 */
    default void generationSkipped(String reasonCode) {}

    /** 通知调用方开始校验当前完整回答的引用。 */
    default void validationStarted() {}

    /**
     * 通知调用方当前回答的引用校验成功。
     *
     * @param citationCount 合法知识引用数量
     */
    default void validationCompleted(int citationCount) {}

    /**
     * 通知调用方当前已完成尝试包含非法引用。
     *
     * @param reasonCode 稳定失败码
     * @param repairScheduled 是否即将清空正文并进行引用修复
     */
    void invalidReferences(String reasonCode, boolean repairScheduled);
  }

  /** 同步兼容入口使用的无副作用观察器。 */
  private static final class NoopObserver implements Observer {
    private static final NoopObserver INSTANCE = new NoopObserver();

    /** 创建单例无副作用观察器。 */
    private NoopObserver() {}

    /** {@inheritDoc} */
    @Override
    public void started(AnswerGenerator.ModelTarget target, AnswerGenerator.AttemptReason reason) {}

    /** {@inheritDoc} */
    @Override
    public void delta(String text) {}

    /** {@inheritDoc} */
    @Override
    public void completed(
        AnswerGenerator.ModelTarget target, String content, String finishReason) {}

    /** {@inheritDoc} */
    @Override
    public void failed(
        AnswerGenerator.ModelTarget target, String partialContent, ApiException error) {}

    /** {@inheritDoc} */
    @Override
    public void invalidReferences(String reasonCode, boolean repairScheduled) {}
  }
}
