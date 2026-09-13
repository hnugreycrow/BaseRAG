package com.hnu.backend.conversation.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ActionRequest(@NotNull UUID clientRequestId) {}
