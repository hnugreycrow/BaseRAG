package com.hnu.backend.rag.pipeline;

import java.util.List;
import java.util.Objects;

/**
 * 结合会话上下文改写后的独立问题及其检索子问题计划。
 *
 * @param standaloneQuestion 可脱离上下文理解的完整问题
 * @param subQuestions 按执行顺序排列的检索子问题
 */
public record QueryPlan(String standaloneQuestion, List<SubQuestion> subQuestions) {
  /** 校验计划并对可变集合进行防御性复制。 */
  public QueryPlan {
    standaloneQuestion = Objects.requireNonNull(standaloneQuestion, "standaloneQuestion");
    subQuestions = List.copyOf(subQuestions);
  }

  /**
   * 在规划模型不可用或输出无效时创建单问题降级计划。
   *
   * @param originalQuestion 原始用户问题
   * @return 仅包含原始问题的查询计划
   */
  public static QueryPlan fallback(String originalQuestion) {
    String question = Objects.requireNonNull(originalQuestion, "originalQuestion").strip();
    return new QueryPlan(question, List.of(new SubQuestion("Q1", question)));
  }
}
