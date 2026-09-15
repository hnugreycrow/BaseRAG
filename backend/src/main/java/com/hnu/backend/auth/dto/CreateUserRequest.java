package com.hnu.backend.auth.dto;

import com.hnu.backend.auth.entity.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 管理员创建账号的请求。
 *
 * @param username 登录名
 * @param displayName 显示名
 * @param password 初始密码
 * @param role 初始角色
 */
public record CreateUserRequest(
    @NotBlank String username,
    @NotBlank String displayName,
    @NotNull String password,
    @NotNull UserRole role) {}
