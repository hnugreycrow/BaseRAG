package com.hnu.backend.model.client;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EmbeddingClientTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void ordersByProviderIndex() {
    var vectors =
        EmbeddingClient.parse(
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
        }) assertThrows(ApiException.class, () -> EmbeddingClient.parse(json.readTree(body), 1, 1));
  }

  @Test
  void rejectsZeroNonNumericAndFloatOverflow() {
    for (String value : new String[] {"0", "\"1\"", "1e100", "null"}) {
      var node = json.readTree("{\"data\":[{\"index\":0,\"embedding\":[" + value + "]}]}");
      assertThrows(ApiException.class, () -> EmbeddingClient.parse(node, 1, 1));
    }
  }
}
