package com.hnu.backend.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建会话；省略思考选项时默认关闭。 */
public record CreateConversationRequest(
    @NotBlank @Size(max = 200) String title, Boolean thinkingEnabled) {}
