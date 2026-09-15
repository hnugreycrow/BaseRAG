package com.hnu.backend.auth.vo;

import com.hnu.backend.auth.entity.UserRole;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 可安全返回给浏览器的用户信息。
 *
 * @param id 用户标识
 * @param username 登录名
 * @param displayName 显示名
 * @param role 账号角色
 * @param enabled 是否启用
 * @param lastLoginAt 最近登录时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record UserResponse(
    UUID id,
    String username,
    String displayName,
    UserRole role,
    boolean enabled,
    OffsetDateTime lastLoginAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
