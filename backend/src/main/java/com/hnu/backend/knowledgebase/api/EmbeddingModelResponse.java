package com.hnu.backend.knowledgebase.api;

public record EmbeddingModelResponse(
    String id, String provider, String model, int dimensions, boolean defaultModel) {}
