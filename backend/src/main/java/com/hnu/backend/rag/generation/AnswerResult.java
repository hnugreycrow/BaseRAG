package com.hnu.backend.rag.generation;

import com.hnu.backend.rag.vo.SourceResponse;
import java.util.List;

/**
 * 阶段七最终回答的不可变结果。
 *
 * @param content 最终可持久化的完整回答正文
 * @param sources 实际进入本轮回答提示词的知识来源
 * @param citations 正文中通过白名单校验的知识引用编号
 * @param toolReferences 正文中通过白名单校验的工具编号，仅供后端审计
 * @param generation 实际成功模型信息；固定无证据回答时为空
 */
public record AnswerResult(
    String content,
    List<SourceResponse> sources,
    List<String> citations,
    List<String> toolReferences,
    AnswerGenerator.Generation generation) {
  /** 冻结来源和引用集合，防止回答完成后被调用方修改。 */
  public AnswerResult {
    sources = List.copyOf(sources);
    citations = List.copyOf(citations);
    toolReferences = List.copyOf(toolReferences);
  }
}
