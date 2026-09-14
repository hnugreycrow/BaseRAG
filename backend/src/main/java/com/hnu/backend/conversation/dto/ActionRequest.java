package com.hnu.backend.conversation.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * 对消息执行操作的幂等请求。
 *
 * @param clientRequestId 客户端生成的幂等请求标识
 */
public record ActionRequest(@NotNull UUID clientRequestId) {}
