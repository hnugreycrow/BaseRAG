package com.hnu.backend.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 文档名称请求。
 *
 * @param name 非空且不超过 255 个字符的文档名称
 */
public record DocumentRequest(@NotBlank @Size(max = 255) String name) {}
