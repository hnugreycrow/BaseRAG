package com.hnu.backend.question.api;

import jakarta.validation.constraints.NotBlank;

public record QuestionRequest(@NotBlank String question) {}
