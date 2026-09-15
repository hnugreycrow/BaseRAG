package com.hnu.backend.auth.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 管理员更新账号状态的请求。
 *
 * @param enabled 是否启用账号
 */
public record UserStatusRequest(@NotNull Boolean enabled) {}
