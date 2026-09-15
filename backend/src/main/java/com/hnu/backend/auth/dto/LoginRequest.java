package com.hnu.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 登录凭据。
 *
 * @param username 用户名
 * @param password 明文密码；只在本次校验期间驻留内存
 */
public record LoginRequest(@NotBlank String username, @NotNull String password) {}
