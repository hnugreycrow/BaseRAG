package com.hnu.backend.question.support;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.question.api.SourceResponse;
import com.hnu.backend.question.domain.SearchHit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ContextBuilder {
  public record Context(String text, List<SourceResponse> sources) {}

  private final RagProperties config;
  private final JsonMapper json = JsonMapper.builder().build();

  public ContextBuilder(RagProperties config) {
    this.config = config;
  }

  public Context build(List<SearchHit> hits) {
    StringBuilder text = new StringBuilder();
    List<SourceResponse> sources = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    for (SearchHit hit : hits) {
      if (!seen.add(hit.getChunkId())) continue;
      String id = "S" + (sources.size() + 1);
      SourceResponse source =
          new SourceResponse(
              id,
              hit.getKnowledgeBaseId(),
              hit.getKnowledgeBaseName(),
              hit.getChunkId(),
              hit.getDocumentId(),
              hit.getVersionId(),
              hit.getDocumentName(),
              hit.getHeading(),
              hit.getLineStart(),
              hit.getLineEnd(),
              hit.getSimilarity(),
              hit.getContent());
      String block = json.writeValueAsString(source) + "\n";
      text.append(block);
      sources.add(source);
    }
    return new Context(text.toString(), List.copyOf(sources));
  }
}
