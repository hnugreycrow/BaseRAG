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
 */
public record RagResult(
    String content,
    List<SourceResponse> sources,
    List<String> citations,
    AnswerGenerator.Generation generation) {
  /** 固定返回集合。 */
  public RagResult {
    sources = List.copyOf(sources);
    citations = List.copyOf(citations);
  }
}
