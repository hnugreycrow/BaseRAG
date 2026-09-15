package com.hnu.backend.auth.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 用户修改本人密码的请求。
 *
 * @param oldPassword 当前密码
 * @param newPassword 新密码
 */
public record ChangePasswordRequest(@NotNull String oldPassword, @NotNull String newPassword) {}
