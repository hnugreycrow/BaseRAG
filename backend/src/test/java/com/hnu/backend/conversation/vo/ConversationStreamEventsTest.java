package com.hnu.backend.conversation.vo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConversationStreamEventsTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void startedKeepsExistingWireFields() {
    UUID conversationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID answerId = UUID.randomUUID();
    var payload =
        new ConversationStreamEvents.Started(1, conversationId, userId, answerId, answerId, 3, 2);

    Map<?, ?> encoded = json.readValue(json.writeValueAsString(payload), Map.class);

    assertEquals(7, encoded.size());
    assertEquals(1, encoded.get("schemaVersion"));
    assertEquals(conversationId.toString(), encoded.get("conversationId"));
    assertEquals(userId.toString(), encoded.get("userMessageId"));
    assertEquals(answerId.toString(), encoded.get("assistantMessageId"));
    assertEquals(answerId.toString(), encoded.get("generationId"));
    assertEquals(3, encoded.get("turnIndex"));
    assertEquals(2, encoded.get("variantIndex"));
  }

  @Test
  void terminalOmitsAbsentErrorFields() {
    var payload =
        new ConversationStreamEvents.PersistedTerminal(1, null, "request", false, null, null);

    Map<?, ?> encoded = json.readValue(json.writeValueAsString(payload), Map.class);

    assertEquals(1, encoded.get("schemaVersion"));
    assertEquals("request", encoded.get("requestId"));
    assertEquals(false, encoded.get("retryable"));
    assertTrue(encoded.containsKey("assistantMessage"));
    assertFalse(encoded.containsKey("code"));
    assertFalse(encoded.containsKey("message"));
  }
}
