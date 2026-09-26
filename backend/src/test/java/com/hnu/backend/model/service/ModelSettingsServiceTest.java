package com.hnu.backend.model.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.model.config.AiProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ModelSettingsServiceTest {
  @Test
  void preservesRuntimeOrderAndOmitsSecretsAndAddresses() {
    AiProperties config = spy(new AiProperties());
    var first =
        new AiProperties.ModelTarget(
            "first",
            "provider",
            "model-a",
            "https://private.example",
            "/secret-path",
            "secret-test-token",
            15000,
            0,
            true);
    var second =
        new AiProperties.ModelTarget(
            "second",
            "provider",
            "model-b",
            "https://private.example",
            "/secret-path",
            "",
            15000,
            0,
            false);
    var embedding =
        new AiProperties.ModelTarget(
            "embedding",
            "provider",
            "vector",
            "https://private.example",
            "/secret-path",
            " ",
            60000,
            1024,
            false);
    var noop = new AiProperties.ModelTarget("skip", "noop", "noop", "", "", "", 60000, 0, false);
    doReturn(List.of(first, second)).when(config).chatModels();
    doReturn(List.of(embedding)).when(config).embeddingModels();
    doReturn(List.of(second, noop)).when(config).rerankModels();
    config.getEmbedding().setDefaultModel("embedding");
    config.getRerank().setDefaultModel("second");

    var result = new ModelSettingsService(config).get();
    assertEquals(List.of("first", "second"), result.chat().stream().map(m -> m.id()).toList());
    assertTrue(result.chat().getFirst().defaultModel());
    assertTrue(result.chat().getFirst().credentialConfigured());
    assertFalse(result.chat().get(1).defaultModel());
    assertFalse(result.chat().get(1).credentialConfigured());
    assertEquals(1024, result.embedding().getFirst().dimensions());
    assertTrue(result.embedding().getFirst().defaultModel());
    assertFalse(result.embedding().getFirst().credentialConfigured());
    assertTrue(result.rerank().getFirst().defaultModel());
    assertTrue(result.rerank().get(1).localFallback());
    String json = JsonMapper.builder().build().writeValueAsString(result);
    assertFalse(json.contains("secret-test-token"));
    assertFalse(json.contains("private.example"));
    assertFalse(json.contains("secret-path"));
    assertFalse(json.contains("apiKey"));
  }
}
