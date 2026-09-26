package com.hnu.backend.shared.json;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.configuration.JsonConfiguration;
import com.hnu.backend.conversation.entity.MessageStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class JsonCodecsTest {
  @Test
  void springProtocolMapperUsesTheSharedWirePolicy() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withUserConfiguration(JsonConfiguration.class)
        .run(
            context -> {
              JsonMapper spring = context.getBean(JsonMapper.class);
              var value =
                  Map.of(
                      "at",
                      OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                      "status",
                      MessageStatus.COMPLETED);
              assertEquals(
                  JsonCodecs.protocol().readTree(JsonCodecs.protocol().writeValueAsString(value)),
                  spring.readTree(spring.writeValueAsString(value)));
              assertEquals(
                  "2026-01-01T00:00:00Z",
                  spring.readTree(spring.writeValueAsString(value)).path("at").asString());
              assertEquals(
                  "COMPLETED",
                  spring.readTree(spring.writeValueAsString(value)).path("status").asString());
            });
  }

  @Test
  void purposesAreIsolatedAndUnknownEnumsRemainErrors() {
    assertNotSame(JsonCodecs.protocol(), JsonCodecs.snapshots());
    assertNotSame(JsonCodecs.snapshots(), JsonCodecs.models());
    assertThrows(
        RuntimeException.class,
        () -> JsonCodecs.snapshots().readValue("\"FUTURE_STATUS\"", MessageStatus.class));
    assertThrows(RuntimeException.class, () -> JsonCodecs.models().readTree("{} {}"));
  }
}
