package com.hnu.backend.rag.planning;

import com.hnu.backend.rag.memory.RagMemory;

public interface QueryPlanner {
  PlanningOutput plan(RagMemory memory, String currentQuestion, int maxSubQuestions);

  record PlanningOutput(String content, String modelId, String provider, String model) {}
}
