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

  @Test
  void validatesSearchFunnelAndResolvesZeroRecallBudget() {
    RagProperties properties = new RagProperties();
    properties.getSearch().setRecallBudget(0);
    properties.validate();
    org.junit.jupiter.api.Assertions.assertEquals(
        properties.getSearch().getDefaultTopK(), properties.getSearch().effectiveRecallBudget());

    properties = new RagProperties();
    properties.getSearch().setRecallBudget(9);
    assertThrows(IllegalArgumentException.class, properties::validate);

    properties = new RagProperties();
    properties.getPipeline().getRerank().setMaxInputCandidates(0);
    assertThrows(IllegalArgumentException.class, properties::validate);

    properties = new RagProperties();
    properties.getPipeline().getRerank().setSelectedEvidence(41);
    assertThrows(IllegalArgumentException.class, properties::validate);

    properties = new RagProperties();
    properties.getPipeline().getRerank().setSelectedEvidence(3);
    assertThrows(IllegalArgumentException.class, properties::validate);

    properties = new RagProperties();
    properties.getPipeline().getDeduplication().setOverlapThreshold(0);
    assertThrows(IllegalArgumentException.class, properties::validate);

    properties = new RagProperties();
    properties.getSearch().getFusion().setRrfK(0);
    assertThrows(IllegalArgumentException.class, properties::validate);

    properties = new RagProperties();
    properties.getSearch().getFusion().getChannelWeights().setVector(Double.NaN);
    assertThrows(IllegalArgumentException.class, properties::validate);
  }
}
