package com.hnu.backend.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TitleRequest(@NotBlank @Size(max = 200) String title) {}
