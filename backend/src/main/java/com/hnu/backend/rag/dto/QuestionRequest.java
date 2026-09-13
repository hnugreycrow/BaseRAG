package com.hnu.backend.rag.dto;

import jakarta.validation.constraints.NotBlank;

public record QuestionRequest(@NotBlank String question) {}
