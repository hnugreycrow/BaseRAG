package com.hnu.backend.auth.service;

import cn.dev33.satoken.stp.StpInterface;
import java.util.List;
import org.springframework.stereotype.Component;

/** 为 Sa-Token 提供数据库中的当前账号角色。 */
@Component
public class SaTokenAuthorizationProvider implements StpInterface {
  private final CurrentUserService currentUserService;

  /**
   * 创建角色提供器。
   *
   * @param currentUserService 当前用户解析服务
   */
  public SaTokenAuthorizationProvider(CurrentUserService currentUserService) {
    this.currentUserService = currentUserService;
  }

  /** 当前版本没有独立权限码模型。 */
  @Override
  public List<String> getPermissionList(Object loginId, String loginType) {
    return List.of();
  }

  /**
   * 返回当前启用账号的角色标识。
   *
   * @param loginId Sa-Token 登录标识
   * @param loginType Sa-Token 账号体系标识
   * @return 与 {@code UserRole} 枚举名一致的单一角色
   */
  @Override
  public List<String> getRoleList(Object loginId, String loginType) {
    return List.of(currentUserService.require(loginId).getRole().name());
  }
}
