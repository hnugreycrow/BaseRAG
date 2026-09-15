package com.hnu.backend.conversation.dto;

import jakarta.validation.constraints.NotNull;

/** 更新当前会话的深度思考选择。 */
public record ThinkingRequest(@NotNull Boolean thinkingEnabled) {}
