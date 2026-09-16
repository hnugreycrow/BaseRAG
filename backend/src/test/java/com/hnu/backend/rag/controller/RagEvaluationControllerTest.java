package com.hnu.backend.rag.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.hnu.backend.auth.service.CurrentUserService;
import com.hnu.backend.configuration.RagProperties;
import org.junit.jupiter.api.Test;

class RagEvaluationControllerTest {
  @Test
  void exposesOnlyNonSensitiveRagParameters() {
    RagProperties rag = new RagProperties();
    rag.setChunkSize(800);
    rag.setChunkMinSize(300);
    rag.setChunkMaxSize(1200);
    rag.setChunkOverlap(100);
    rag.getSearch().setDefaultTopK(8);
    rag.getSearch().setRecallBudget(18);
    rag.getSearch().getChannels().setTimeoutMs(9000);
    rag.getSearch().getFusion().setRrfK(30);
    rag.getPipeline().getRerank().setMaxInputCandidates(36);
    rag.getPipeline().getRerank().setSelectedEvidence(7);
    rag.getPipeline().getDeduplication().setOverlapThreshold(0.9);
    rag.getSearch().getChannels().getVector().setEnabled(false);
    rag.setMaxQuestionChars(1500);
    rag.getPipeline().setMaxSubQuestions(6);
    rag.getPipeline().getRouting().setConfidenceThreshold(0.8);
    rag.getPipeline().getRouting().setTimeoutMs(4000);
    rag.getPipeline().getMcp().setEnabled(true);

    var result = new RagEvaluationController(rag, mock(CurrentUserService.class)).config();

    assertEquals(800, result.chunkSize());
    assertEquals(300, result.chunkMinSize());
    assertEquals(1200, result.chunkMaxSize());
    assertEquals(100, result.chunkOverlap());
    assertEquals(7, result.topK());
    assertEquals(18, result.recallBudget());
    assertEquals(9000, result.channelTimeoutMs());
    assertEquals(30, result.rrfK());
    assertEquals(36, result.rerankCandidateLimit());
    assertEquals(false, result.vectorEnabled());
    assertEquals(1500, result.maxQuestionChars());
    assertEquals(6, result.maxSubQuestions());
    assertEquals(0.8, result.routingConfidenceThreshold());
    assertEquals(4000, result.routingTimeoutMs());
    assertEquals(true, result.mcpEnabled());
    assertEquals(0.9, result.deduplicationOverlapThreshold());
    assertEquals(true, result.rerankEnabled());
    assertEquals(7, result.selectedEvidence());
  }
}
