package com.hnu.backend.document.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DocumentRequest(@NotBlank @Size(max = 255) String name) {}
