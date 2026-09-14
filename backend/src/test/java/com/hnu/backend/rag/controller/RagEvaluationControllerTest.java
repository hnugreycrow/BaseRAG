package com.hnu.backend.rag.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    rag.setTopK(8);
    rag.setMaxQuestionChars(1500);

    var result = new RagEvaluationController(rag).config();

    assertEquals(800, result.chunkSize());
    assertEquals(300, result.chunkMinSize());
    assertEquals(1200, result.chunkMaxSize());
    assertEquals(100, result.chunkOverlap());
    assertEquals(8, result.topK());
    assertEquals(1500, result.maxQuestionChars());
  }
}
