package com.hnu.backend.auth.vo;

/**
 * 浏览器认证状态。
 *
 * @param user 当前用户
 * @param csrfToken 当前 Token Session 对应的 CSRF nonce
 */
public record AuthSessionResponse(UserResponse user, String csrfToken) {}
