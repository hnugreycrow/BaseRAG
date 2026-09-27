package com.hnu.backend.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建会话；省略思考选项时默认关闭。
 *
 * @param title 非空白初始标题，最多 200 个 UTF-16 代码单元
 * @param thinkingEnabled 新回答默认思考开关；为空时按关闭处理
 */
public record CreateConversationRequest(
    @NotBlank @Size(max = 200) String title, Boolean thinkingEnabled) {}
