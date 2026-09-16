package com.hnu.backend.auth.service;

import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.entity.UserRole;
import com.hnu.backend.auth.mapper.UserMapper;
import com.hnu.backend.auth.vo.UserResponse;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.web.PageResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 管理员账号管理规则，包括账号创建、密码重置和防止管理员锁死。 */
@Service
public class AdminUserService {
  private final UserMapper users;
  private final AccountPolicy policy;
  private final PasswordEncoder passwords;
  private final SessionRevocationService revocations;
  private final TransactionTemplate tx;

  /**
   * 创建管理员账号服务。
   *
   * @param users 用户数据访问接口
   * @param policy 账号字段策略
   * @param passwords BCrypt 编码器
   * @param revocations 会话撤销服务
   * @param tx 事务模板
   */
  public AdminUserService(
      UserMapper users,
      AccountPolicy policy,
      PasswordEncoder passwords,
      SessionRevocationService revocations,
      TransactionTemplate tx) {
    this.users = users;
    this.policy = policy;
    this.passwords = passwords;
    this.revocations = revocations;
    this.tx = tx;
  }

  /**
   * 分页查询用户。
   *
   * @param page 页码
   * @param pageSize 每页数量
   * @param rawQuery 可选搜索词
   * @return 用户分页
   * @throws ApiException 分页参数超出允许范围时抛出
   */
  public PageResponse<UserResponse> list(int page, int pageSize, String rawQuery) {
    if (page < 1 || pageSize < 1 || pageSize > 100) {
      throw ApiException.bad("INVALID_PAGE", "页码应大于 0，每页数量应为 1 到 100");
    }
    String query = rawQuery == null || rawQuery.isBlank() ? null : rawQuery.strip();
    long total = users.count(query);
    List<UserResponse> items =
        users.list(query, pageSize, (long) (page - 1) * pageSize).stream()
            .map(AuthService::toResponse)
            .toList();
    return PageResponse.of(items, total, page, pageSize);
  }

  /**
   * 创建账号。新账号不附带知识库；管理员可在账号创建后维护公共知识库。
   *
   * @param rawUsername 原始用户名
   * @param rawDisplayName 原始显示名
   * @param rawPassword 初始密码
   * @param role 初始角色
   * @return 新用户
   * @throws ApiException 账号字段非法或用户名重复时抛出
   */
  public UserResponse create(
      String rawUsername, String rawDisplayName, String rawPassword, UserRole role) {
    String username = policy.username(rawUsername);
    String displayName = policy.displayName(rawDisplayName);
    String password = policy.password(rawPassword);
    try {
      User created =
          tx.execute(
              ignored -> {
                User user = newUser(username, displayName, password, role);
                users.insert(user);
                return user;
              });
      return AuthService.toResponse(users.find(created.getId()));
    } catch (DataIntegrityViolationException error) {
      throw ApiException.conflict("USERNAME_EXISTS", "用户名已存在");
    }
  }

  /**
   * 启用或禁用账号，禁止自禁用和禁用最后一个管理员。
   *
   * @param actorId 当前管理员标识
   * @param targetId 目标用户标识
   * @param enabled 目标状态
   * @return 更新后的用户
   * @throws ApiException 目标不存在、自禁用或操作会禁用最后一个管理员时抛出
   */
  public UserResponse setEnabled(UUID actorId, UUID targetId, boolean enabled) {
    if (!enabled && actorId.equals(targetId)) {
      throw ApiException.conflict("SELF_DISABLE_NOT_ALLOWED", "不能禁用当前登录账号");
    }
    User updated =
        tx.execute(
            ignored -> {
              User target = require(targetId);
              if (!enabled && target.isEnabled() && target.getRole() == UserRole.ADMIN) {
                // 行锁使两个管理员无法并发禁用彼此后同时通过“最后一个管理员”检查。
                users.lockEnabledAdmins();
                if (users.countEnabledAdmins() <= 1) {
                  throw ApiException.conflict("LAST_ADMIN_REQUIRED", "不能禁用最后一个启用的管理员");
                }
              }
              target.setEnabled(enabled);
              target.setUpdatedAt(OffsetDateTime.now());
              users.updateById(target);
              // Redis 撤销失败会向上传播，使数据库事务回滚，禁止保留无法撤销的旧会话。
              if (!enabled) revocations.revokeAll(targetId);
              return target;
            });
    return AuthService.toResponse(updated);
  }

  /**
   * 重置用户密码并撤销全部旧会话。
   *
   * @param targetId 目标用户标识
   * @param rawPassword 新密码
   * @throws ApiException 目标不存在或密码不符合 BCrypt 边界时抛出
   */
  public void resetPassword(UUID targetId, String rawPassword) {
    User target = require(targetId);
    String password = policy.password(rawPassword);
    tx.executeWithoutResult(
        ignored -> {
          target.setPasswordHash(passwords.encode(password));
          target.setUpdatedAt(OffsetDateTime.now());
          users.updateById(target);
          revocations.revokeAll(targetId);
        });
  }

  /**
   * 创建尚未持久化的用户实体。
   *
   * @param username 规范化用户名
   * @param displayName 合法显示名
   * @param password 合法明文密码
   * @param role 账号角色
   * @return 初始化完成的用户
   */
  private User newUser(String username, String displayName, String password, UserRole role) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user.setDisplayName(displayName);
    user.setPasswordHash(passwords.encode(password));
    user.setRole(role);
    user.setEnabled(true);
    return user;
  }

  /**
   * 加载目标用户。
   *
   * @param id 用户标识
   * @return 用户实体
   */
  private User require(UUID id) {
    User user = users.find(id);
    if (user == null) throw ApiException.notFound("USER_NOT_FOUND", "用户不存在");
    return user;
  }
}
