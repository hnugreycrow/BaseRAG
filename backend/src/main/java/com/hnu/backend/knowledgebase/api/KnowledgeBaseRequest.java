package com.hnu.backend.knowledgebase.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeBaseRequest(
    @NotBlank @Size(max = 200) String name, String embeddingModelId) {}
