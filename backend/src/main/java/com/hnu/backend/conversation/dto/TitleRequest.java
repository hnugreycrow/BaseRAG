package com.hnu.backend.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改会话标题的请求。
 *
 * @param title 非空且不超过 200 个字符的新标题
 */
public record TitleRequest(@NotBlank @Size(max = 200) String title) {}
