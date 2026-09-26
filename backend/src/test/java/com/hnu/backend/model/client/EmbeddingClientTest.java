package com.hnu.backend.model.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.model.config.EmbeddingProtocol;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EmbeddingClientTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void ordersByProviderIndex() {
    var vectors =
        OpenAICompatibleEmbeddingAdapter.parse(
            json.readTree(
                """
            {"data":[{"index":1,"embedding":[0,1]},{"index":0,"embedding":[1,0]}]}
            """),
            2,
            2);
    assertArrayEquals(new float[] {1, 0}, vectors.getFirst());
  }

  @Test
  void rejectsWrongCountDuplicateIndexAndDimension() {
    for (String body :
        new String[] {
          "{\"data\":[]}",
          "{\"data\":[{\"index\":0,\"embedding\":[1]},{\"index\":0,\"embedding\":[1]}]}",
          "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}",
          "{\"data\":[{\"index\":-1,\"embedding\":[1]}]}",
          "{\"data\":[{\"index\":0.5,\"embedding\":[1]}]}"
        }) {
      assertThrows(
          ApiException.class,
          () -> OpenAICompatibleEmbeddingAdapter.parse(json.readTree(body), 1, 1));
    }
  }

  @Test
  void rejectsZeroNonNumericAndFloatOverflow() {
    for (String value : new String[] {"0", "\"1\"", "1e100", "null"}) {
      var node = json.readTree("{\"data\":[{\"index\":0,\"embedding\":[" + value + "]}]}");
      assertThrows(ApiException.class, () -> OpenAICompatibleEmbeddingAdapter.parse(node, 1, 1));
    }
  }

  @Test
  void fixedBindingRejectsChangedProviderAndMissingModelWithoutFallback() {
    AiProperties config = new AiProperties();
    AiProperties.Provider provider = new AiProperties.Provider();
    provider.setUrl("https://example.com");
    provider.setApiKey("test-only");
    provider.getEndpoints().setEmbedding("/v1/embeddings");
    config.getProviders().put("provider-new", provider);
    AiProperties.Candidate candidate = new AiProperties.Candidate();
    candidate.setId("fixed-id");
    candidate.setProvider("provider-new");
    candidate.setModel("same-model");
    candidate.setDimension(1536);
    config.getEmbedding().getCandidates().add(candidate);
    config.getEmbedding().setDefaultModel("fixed-id");
    EmbeddingAdapter adapter = mock(EmbeddingAdapter.class);
    when(adapter.protocol()).thenReturn(EmbeddingProtocol.OPENAI_COMPATIBLE);
    EmbeddingClient client = new EmbeddingClient(config, java.util.List.of(adapter));

    ApiException changed =
        assertThrows(
            ApiException.class,
            () ->
                client.embed(
                    "fixed-id", "provider-old", "same-model", 1536, java.util.List.of("q")));
    assertEquals("EMBEDDING_BINDING_CHANGED", changed.code());
    ApiException missing =
        assertThrows(
            ApiException.class,
            () ->
                client.embed(
                    "removed-id", "provider-new", "same-model", 1536, java.util.List.of("q")));
    assertEquals("EMBEDDING_MODEL_UNAVAILABLE", missing.code());
    verify(adapter, never()).embed(any(), any());
  }
}
