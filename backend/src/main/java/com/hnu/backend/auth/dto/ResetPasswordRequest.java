package com.hnu.backend.auth.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 管理员重置密码的请求。
 *
 * @param password 新密码
 */
public record ResetPasswordRequest(@NotNull String password) {}
