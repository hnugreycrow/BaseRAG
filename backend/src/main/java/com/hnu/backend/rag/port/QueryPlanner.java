package com.hnu.backend.rag.port;

import com.hnu.backend.rag.model.RagMemory;

public interface QueryPlanner {
  PlanningOutput plan(RagMemory memory, String currentQuestion, int maxSubQuestions);

  record PlanningOutput(String content, String modelId, String provider, String model) {}
}
