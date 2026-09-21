package com.hnu.backend.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.mapper.UserMapper;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 将 Sa-Token 登录标识解析为每次请求都重新校验的数据库用户。 */
@Service
public class CurrentUserService {
  private final UserMapper userMapper;

  /**
   * 创建当前用户解析服务。
   *
   * @param userMapper 用户数据访问接口
   */
  public CurrentUserService(UserMapper userMapper) {
    this.userMapper = userMapper;
  }

  /**
   * 获取当前启用用户。
   *
   * @return 当前用户
   * @throws ApiException 登录用户不存在或已禁用时抛出稳定认证异常
   */
  public User require() {
    return require(StpUtil.getLoginIdAsString());
  }

  /**
   * 将 Sa-Token 登录标识解析为当前启用用户。
   *
   * @param loginId Sa-Token 登录标识
   * @return 当前启用用户
   * @throws ApiException 登录用户不存在或已禁用时抛出稳定认证异常
   */
  User require(Object loginId) {
    UUID id;
    try {
      id = UUID.fromString(String.valueOf(loginId));
    } catch (IllegalArgumentException error) {
      StpUtil.logout();
      throw new ApiException(ErrorCode.AUTH_REQUIRED, "请先登录");
    }
    User user = userMapper.find(id);
    if (user == null) {
      StpUtil.logout(id.toString());
      throw new ApiException(ErrorCode.AUTH_REQUIRED, "请先登录");
    }
    if (!user.isEnabled()) {
      StpUtil.logout(id.toString());
      throw new ApiException(ErrorCode.ACCOUNT_DISABLED, "账号已被禁用");
    }
    return user;
  }
}
