package com.hnu.backend.document.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DocumentProcessingPropertiesTest {
  @Test
  void defaultsPreserveCurrentQueueLimits() {
    var config = new DocumentProcessingProperties();

    config.validate();

    assertEquals(2, config.getSyncConcurrency());
    assertEquals(2, config.getWorkers());
    assertEquals(50, config.getQueueCapacity());
  }

  @Test
  void rejectsNonPositiveAndOverflowingCapacity() {
    var config = new DocumentProcessingProperties();
    config.setQueueCapacity(0);
    assertThrows(IllegalArgumentException.class, config::validate);

    config.setQueueCapacity(Integer.MAX_VALUE);
    assertThrows(IllegalArgumentException.class, config::validate);
  }
}
