package com.hnu.backend.rag.vo;

public record RagEvaluationConfigResponse(
    int chunkSize,
    int chunkMinSize,
    int chunkMaxSize,
    int chunkOverlap,
    int topK,
    int maxQuestionChars) {}
