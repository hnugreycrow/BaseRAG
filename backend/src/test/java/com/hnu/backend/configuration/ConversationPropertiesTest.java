package com.hnu.backend.configuration;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ConversationPropertiesTest {
  @Test
  void rejectsOverlapWithoutAnUncoveredRecentWindow() {
    ConversationProperties properties = new ConversationProperties();
    properties.setSummaryBatchTurns(8);
    assertThrows(IllegalArgumentException.class, properties::validate);
  }

  @Test
  void rejectsNonPositiveSummaryLengthLimit() {
    ConversationProperties properties = new ConversationProperties();
    properties.setSummaryMaxChars(0);
    assertThrows(IllegalArgumentException.class, properties::validate);
  }
}
