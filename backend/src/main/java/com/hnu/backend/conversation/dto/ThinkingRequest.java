package com.hnu.backend.conversation.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 更新当前会话的深度思考选择，仅影响随后创建的回答版本。
 *
 * @param thinkingEnabled 必填的默认思考开关
 */
public record ThinkingRequest(@NotNull Boolean thinkingEnabled) {}
