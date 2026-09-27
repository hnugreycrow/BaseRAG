package com.hnu.backend.rag.generation;

import com.hnu.backend.rag.vo.SourceResponse;
import java.util.List;

/** 回答引用白名单校验与正文归一化契约。 */
public interface CitationPolicy {
  /** 返回按首次出现顺序排列的引用；非法引用抛出 IllegalArgumentException。 */
  Citations.Validation validate(String content, List<SourceResponse> sources, List<String> toolIds);

  /** 规范化引用位置，保持原有 Markdown 语义。 */
  String normalize(String content);
}
