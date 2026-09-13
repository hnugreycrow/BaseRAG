package com.hnu.backend.knowledgebase.vo;

public record EmbeddingModelResponse(
    String id, String provider, String model, int dimensions, boolean defaultModel) {}
