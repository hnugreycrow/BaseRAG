package com.hnu.backend.rag.pipeline;

import java.util.Objects;

/**
 * 查询计划中的一个可独立检索子问题。
 *
 * @param id 连续且稳定的子问题标识，如 Q1
 * @param question 子问题文本
 */
public record SubQuestion(String id, String question) {
  /** 校验并创建子问题。 */
  public SubQuestion {
    id = Objects.requireNonNull(id, "id");
    question = Objects.requireNonNull(question, "question");
  }
}
