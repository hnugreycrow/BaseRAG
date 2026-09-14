package com.hnu.backend.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * 新建用户消息的请求。
 *
 * @param clientMessageId 客户端生成的幂等请求标识
 * @param content 非空且不超过 2000 个字符的消息正文
 */
public record MessageRequest(
    @NotNull UUID clientMessageId, @NotBlank @Size(max = 2000) String content) {}
