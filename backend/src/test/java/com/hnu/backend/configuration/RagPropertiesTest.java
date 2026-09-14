package com.hnu.backend.configuration;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class RagPropertiesTest {
  @Test
  void rejectsInvalidRoutingAndMcpConfiguration() {
    RagProperties properties = new RagProperties();
    properties.getPipeline().getRouting().setConfidenceThreshold(1.1);
    assertThrows(IllegalArgumentException.class, properties::validate);

    properties = new RagProperties();
    properties.getPipeline().getRouting().setTimeoutMs(0);
    assertThrows(IllegalArgumentException.class, properties::validate);

    properties = new RagProperties();
    properties.getPipeline().getMcp().setTimeoutMs(0);
    assertThrows(IllegalArgumentException.class, properties::validate);

    properties = new RagProperties();
    properties.getPipeline().getMcp().setMaxOutputChars(0);
    assertThrows(IllegalArgumentException.class, properties::validate);

    properties = new RagProperties();
    properties.getPipeline().getMcp().setAllowList(List.of(" "));
    assertThrows(IllegalArgumentException.class, properties::validate);
  }
}
