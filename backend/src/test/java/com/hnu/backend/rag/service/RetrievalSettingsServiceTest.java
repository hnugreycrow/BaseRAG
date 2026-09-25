package com.hnu.backend.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.configuration.ConversationProperties;
import com.hnu.backend.configuration.RagProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RetrievalSettingsServiceTest {
  @Test
  void resolvesAutomaticBudgetAndExcludesStorageSecrets() {
    var rag = new RagProperties();
    rag.getSearch().setDefaultTopK(13);
    rag.getSearch().setRecallBudget(0);
    rag.getSearch().getChannels().getVector().setEnabled(false);
    rag.getPipeline().getRerank().setEnabled(false);
    rag.getPipeline().getRerank().setSelectedEvidence(6);
    rag.getStorage().setSecretKey("private-test-secret");
    rag.getStorage().setEndpoint("https://private.example");
    var conversation = new ConversationProperties();
    conversation.setRecentTurns(10);
    conversation.setSummaryMaxChars(500);
    var response = new RetrievalSettingsService(rag, conversation).get();
    assertEquals(13, response.recallBudget());
    assertFalse(response.vectorEnabled());
    assertFalse(response.rerankEnabled());
    assertEquals(6, response.selectedEvidenceLimit());
    assertEquals(10, response.recentTurns());
    assertEquals(500, response.summaryMaxChars());
    var json = JsonMapper.builder().build().writeValueAsString(response);
    assertFalse(json.contains("private-test-secret"));
    assertFalse(json.contains("private.example"));
    assertFalse(json.contains("storage"));
  }

  @Test
  void readsExplicitBudgetAndCurrentPlanningConfiguration() {
    var rag = new RagProperties();
    rag.getSearch().setRecallBudget(33);
    rag.getPipeline().getPlanning().setRecentTurns(3);
    rag.getPipeline().getRouting().setTimeoutMs(2400);
    rag.getSearch().getFusion().setRrfK(45);
    var response = new RetrievalSettingsService(rag, new ConversationProperties()).get();
    assertEquals(33, response.recallBudget());
    assertEquals(3, response.planningRecentTurns());
    assertEquals(2400, response.routingTimeoutMs());
    assertEquals(45, response.rrfK());
  }
}
