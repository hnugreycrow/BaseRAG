package com.hnu.backend.model.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class AiPropertiesRerankTest {
  @Test
  void validatesStreamBudgetsAndInteractiveDefaults() {
    AiProperties defaults = new AiProperties();
    assertEquals(10_000, defaults.getStream().getFirstContentTimeoutMs());
    assertEquals(15_000, defaults.getStream().getIdleTimeoutMs());
    assertEquals(180_000, defaults.getStream().getTotalTimeoutMs());
    for (int timeout : new int[] {0, -1}) {
      for (int field = 0; field < 3; field++) {
        AiProperties invalid = new AiProperties();
        switch (field) {
          case 0 -> invalid.getStream().setFirstContentTimeoutMs(timeout);
          case 1 -> invalid.getStream().setIdleTimeoutMs(timeout);
          case 2 -> invalid.getStream().setTotalTimeoutMs(timeout);
          default -> throw new AssertionError();
        }
        assertEquals(
            "Invalid AI selection, stream or batch configuration",
            assertThrows(IllegalArgumentException.class, invalid::validate).getMessage());
      }
    }
  }

  @Test
  void rejectsNonPositiveConnectionTimeoutBeforeResolvingModels() {
    for (int timeout : new int[] {0, -1}) {
      AiProperties config = new AiProperties();
      config.setConnectTimeoutMs(timeout);
      var error = assertThrows(IllegalArgumentException.class, config::validate);
      assertEquals("Invalid AI selection, stream or batch configuration", error.getMessage());
    }
  }

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
  void embeddingAndRerankUseSharedHttpRequestTimeout() {
    AiProperties config = configured();
    config.setRequestTimeoutMs(42_000);
    config.getProviders().get("bailian").getEndpoints().setEmbedding("/embeddings");
    AiProperties.Candidate embedding = candidate("embedding", "bailian", "embedding-model", 1);
    embedding.setDimension(2);
    config.getEmbedding().setDefaultModel("embedding");
    config.getEmbedding().setCandidates(List.of(embedding));

    assertEquals(42_000, config.embeddingModel().timeoutMs());
    assertEquals(42_000, config.rerankModels().getFirst().timeoutMs());
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
