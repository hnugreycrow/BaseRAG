package com.hnu.backend.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.mapper.UserMapper;
import com.hnu.backend.auth.vo.AuthSessionResponse;
import com.hnu.backend.auth.vo.UserResponse;
import com.hnu.backend.shared.error.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 处理登录、会话恢复、注销和用户本人改密。 */
@Service
public class AuthService {
  private final UserMapper users;
  private final AccountPolicy policy;
  private final PasswordEncoder passwords;
  private final LoginRateLimiter limiter;
  private final CsrfTokenService csrfTokens;
  private final CurrentUserService currentUsers;
  private final SessionRevocationService revocations;
  private final TransactionTemplate tx;
  private final String dummyHash;

  /**
   * 创建认证服务。
   *
   * @param users 用户数据访问接口
   * @param policy 账号字段策略
   * @param passwords BCrypt 编码器
   * @param limiter 登录失败限流器
   * @param csrfTokens CSRF nonce 服务
   * @param currentUsers 当前用户解析服务
   * @param revocations 会话撤销服务
   * @param tx 事务模板
   */
  public AuthService(
      UserMapper users,
      AccountPolicy policy,
      PasswordEncoder passwords,
      LoginRateLimiter limiter,
      CsrfTokenService csrfTokens,
      CurrentUserService currentUsers,
      SessionRevocationService revocations,
      TransactionTemplate tx) {
    this.users = users;
    this.policy = policy;
    this.passwords = passwords;
    this.limiter = limiter;
    this.csrfTokens = csrfTokens;
    this.currentUsers = currentUsers;
    this.revocations = revocations;
    this.tx = tx;
    this.dummyHash = passwords.encode("dummy-password-used-for-timing-only");
  }

  /**
   * 校验凭据、创建 Cookie 会话并签发 CSRF nonce。
   *
   * @param rawUsername 原始用户名
   * @param password 明文密码
   * @param clientAddress 直接客户端地址
   * @return 新认证状态
   * @throws ApiException 凭据错误、账号禁用、字段非法或限流时抛出
   */
  public AuthSessionResponse login(String rawUsername, String password, String clientAddress) {
    String username = policy.username(rawUsername);
    limiter.requireAllowed(username, clientAddress);
    User user = users.findByUsername(username);
    String expectedHash = user == null ? dummyHash : user.getPasswordHash();
    if (!passwords.matches(password == null ? "" : password, expectedHash)) {
      limiter.recordFailure(username, clientAddress);
      throw new ApiException("INVALID_CREDENTIALS", "用户名或密码错误", HttpStatus.UNAUTHORIZED);
    }
    if (!user.isEnabled()) {
      throw new ApiException("ACCOUNT_DISABLED", "账号已被禁用", HttpStatus.FORBIDDEN);
    }
    limiter.clear(username, clientAddress);
    user.setLastLoginAt(java.time.OffsetDateTime.now());
    user.setUpdatedAt(java.time.OffsetDateTime.now());
    users.updateById(user);
    loginUser(user.getId());
    return session(user);
  }

  /**
   * 恢复当前登录态并轮换 CSRF nonce。
   *
   * @return 当前认证状态
   * @throws ApiException 数据库账号已删除或禁用时抛出
   */
  public AuthSessionResponse restore() {
    return session(currentUsers.require());
  }

  /** 注销当前浏览器 Token；其他会话保持不变。 */
  public void logout() {
    StpUtil.logout();
  }

  /**
   * 验证旧密码后修改本人密码，撤销全部旧会话并创建新的当前会话。
   *
   * @param oldPassword 当前密码
   * @param newPassword 新密码
   * @return 新认证状态
   * @throws ApiException 旧密码错误或新密码不符合 BCrypt 边界时抛出
   */
  public AuthSessionResponse changePassword(String oldPassword, String newPassword) {
    User current = currentUsers.require();
    if (!passwords.matches(oldPassword == null ? "" : oldPassword, current.getPasswordHash())) {
      throw new ApiException("INVALID_CREDENTIALS", "当前密码错误", HttpStatus.UNAUTHORIZED);
    }
    String validated = policy.password(newPassword);
    tx.executeWithoutResult(
        ignored -> {
          current.setPasswordHash(passwords.encode(validated));
          current.setUpdatedAt(java.time.OffsetDateTime.now());
          users.updateById(current);
          // Redis 撤销失败会抛出异常并回滚密码更新；额外注销比遗留旧会话更安全。
          revocations.revokeAll(current.getId());
        });
    loginUser(current.getId());
    return session(users.find(current.getId()));
  }

  /**
   * 写入非持久浏览器 Cookie 的独立 Token。
   *
   * @param userId 用户标识
   */
  private void loginUser(UUID userId) {
    StpUtil.login(userId.toString(), new SaLoginParameter().setIsLastingCookie(false));
  }

  /**
   * 将用户转换为安全响应并签发新 nonce。
   *
   * @param user 当前用户
   * @return 认证状态
   */
  private AuthSessionResponse session(User user) {
    return new AuthSessionResponse(toResponse(user), csrfTokens.issue());
  }

  /**
   * 删除密码哈希后构造用户响应。
   *
   * @param user 用户实体
   * @return 安全用户响应
   */
  public static UserResponse toResponse(User user) {
    return new UserResponse(
        user.getId(),
        user.getUsername(),
        user.getDisplayName(),
        user.getRole(),
        user.isEnabled(),
        user.getLastLoginAt(),
        user.getCreatedAt(),
        user.getUpdatedAt());
  }
}
