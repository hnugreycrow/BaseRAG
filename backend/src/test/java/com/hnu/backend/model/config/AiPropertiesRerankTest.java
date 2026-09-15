package com.hnu.backend.model.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class AiPropertiesRerankTest {
  @Test
  void putsDefaultFirstThenOrdersRemainingCandidatesByPriority() {
    AiProperties config = configured();
    config.getRerank().setDefaultModel("secondary");

    List<AiProperties.ModelTarget> models = config.rerankModels();

    assertEquals(
        List.of("secondary", "primary", "rerank-noop"),
        models.stream().map(AiProperties.ModelTarget::id).toList());
  }

  @Test
  void embeddingAddressOverrideDoesNotChangeBailianRerankAddress() {
    AiProperties config = configured();
    config
        .getProviders()
        .get("bailian")
        .getEndpoints()
        .setEmbedding("/compatible-mode/v1/embeddings");
    AiProperties.Candidate embedding = candidate("embedding", "bailian", "embedding-model", 1);
    embedding.setDimension(1536);
    embedding.setBaseUrl("https://workspace.example.com");
    config.getEmbedding().setDefaultModel("embedding");
    config.getEmbedding().setCandidates(List.of(embedding));

    assertEquals("https://workspace.example.com", config.embeddingModel().baseUrl());
    assertEquals("https://example.com", config.rerankModels().getFirst().baseUrl());
    assertEquals("/compatible-mode/v1/embeddings", config.embeddingModel().endpoint());
  }

  @Test
  void rejectsUnknownDefaultDuplicateIdsAndIncompleteNoop() {
    AiProperties config = configured();
    config.getRerank().setDefaultModel("missing");
    assertThrows(IllegalArgumentException.class, config::rerankModels);

    config = configured();
    config
        .getRerank()
        .setCandidates(
            List.of(candidate("same", "bailian", "a", 1), candidate("same", "bailian", "b", 2)));
    assertThrows(IllegalArgumentException.class, config::rerankModels);

    config = configured();
    config.getRerank().setDefaultModel("bad-noop");
    config.getRerank().setCandidates(List.of(candidate("bad-noop", "noop", "remote", 1)));
    assertThrows(IllegalArgumentException.class, config::rerankModels);
  }

  private AiProperties configured() {
    AiProperties config = new AiProperties();
    AiProperties.Provider provider = new AiProperties.Provider();
    provider.setUrl("https://example.com");
    provider.setApiKey("test-only");
    provider.getEndpoints().setRerank("/reranks");
    config.getProviders().put("bailian", provider);
    config.getRerank().setDefaultModel("primary");
    config
        .getRerank()
        .setCandidates(
            List.of(
                candidate("primary", "bailian", "primary", 10),
                candidate("secondary", "bailian", "secondary", 20),
                candidate("rerank-noop", "noop", "noop", 100)));
    return config;
  }

  private AiProperties.Candidate candidate(String id, String provider, String model, int priority) {
    AiProperties.Candidate value = new AiProperties.Candidate();
    value.setId(id);
    value.setProvider(provider);
    value.setModel(model);
    value.setPriority(priority);
    return value;
  }
}
