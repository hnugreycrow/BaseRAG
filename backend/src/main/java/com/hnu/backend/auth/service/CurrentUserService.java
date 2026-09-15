package com.hnu.backend.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.entity.UserRole;
import com.hnu.backend.auth.mapper.UserMapper;
import com.hnu.backend.shared.error.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 将 Sa-Token 登录标识解析为每次请求都重新校验的数据库用户。 */
@Service
public class CurrentUserService {
  private final UserMapper users;

  /**
   * 创建当前用户解析服务。
   *
   * @param users 用户数据访问接口
   */
  public CurrentUserService(UserMapper users) {
    this.users = users;
  }

  /**
   * 获取当前启用用户。
   *
   * @return 当前用户
   * @throws ApiException 登录用户不存在或已禁用时抛出稳定认证异常
   */
  public User require() {
    UUID id;
    try {
      id = UUID.fromString(StpUtil.getLoginIdAsString());
    } catch (IllegalArgumentException error) {
      StpUtil.logout();
      throw new ApiException("AUTH_REQUIRED", "请先登录", HttpStatus.UNAUTHORIZED);
    }
    User user = users.find(id);
    if (user == null) {
      StpUtil.logout();
      throw new ApiException("AUTH_REQUIRED", "请先登录", HttpStatus.UNAUTHORIZED);
    }
    if (!user.isEnabled()) {
      StpUtil.logout(id.toString());
      throw new ApiException("ACCOUNT_DISABLED", "账号已被禁用", HttpStatus.FORBIDDEN);
    }
    return user;
  }

  /**
   * 获取当前管理员。
   *
   * @return 当前管理员
   * @throws ApiException 当前用户不是管理员时抛出 403
   */
  public User requireAdmin() {
    User user = require();
    if (user.getRole() != UserRole.ADMIN) {
      throw new ApiException("FORBIDDEN", "当前账号无权执行此操作", HttpStatus.FORBIDDEN);
    }
    return user;
  }
}
