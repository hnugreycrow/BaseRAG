package com.hnu.backend.rag.api;

import com.hnu.backend.rag.vo.SourceResponse;
import java.util.List;

/**
 * 引擎完成后的不可变回答。
 *
 * @param content 正文
 * @param sources 引用来源
 * @param citations 已验证引用
 * @param generation 最终模型信息；未调用模型时为空
 * @param clarification 待澄清上下文；普通回答为空
 */
public record RagResult(
    String content,
    List<SourceResponse> sources,
    List<String> citations,
    AnswerGenerator.Generation generation,
    ClarificationContext clarification) {
  /** 兼容普通回答结果。 */
  public RagResult(
      String content,
      List<SourceResponse> sources,
      List<String> citations,
      AnswerGenerator.Generation generation) {
    this(content, sources, citations, generation, null);
  }

  /** 区分等待澄清和普通回答，不依赖正文判断。 */
  public String outcome() {
    return clarification == null ? "ANSWER" : "CLARIFICATION_REQUIRED";
  }

  /** 固定返回集合。 */
  public RagResult {
    sources = List.copyOf(sources);
    citations = List.copyOf(citations);
  }
}
