package com.hnu.backend.knowledgebase.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeBaseResponse(
    UUID id,
    String name,
    String embeddingModel,
    Integer embeddingDimensions,
    long documentCount,
    OffsetDateTime createdAt) {}
