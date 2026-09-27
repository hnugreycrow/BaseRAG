package com.hnu.backend.rag.generation;

import com.hnu.backend.rag.vo.SourceResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/** 保留原有引用规则的默认实现。 */
@Component
public final class DefaultCitationPolicy implements CitationPolicy {
  @Override
  public Citations.Validation validate(
      String content, List<SourceResponse> sources, List<String> toolIds) {
    return Citations.validate(content, sources, toolIds);
  }

  @Override
  public String normalize(String content) {
    return Citations.normalize(content);
  }
}
