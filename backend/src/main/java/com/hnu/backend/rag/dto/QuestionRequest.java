package com.hnu.backend.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record QuestionRequest(
    @NotBlank String question, @Size(min = 1, max = 20) List<UUID> knowledgeBaseIds) {}
