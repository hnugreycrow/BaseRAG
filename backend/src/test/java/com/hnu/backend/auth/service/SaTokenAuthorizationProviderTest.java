package com.hnu.backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.entity.UserRole;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SaTokenAuthorizationProviderTest {
  private final CurrentUserService currentUserService = mock(CurrentUserService.class);
  private final SaTokenAuthorizationProvider provider =
      new SaTokenAuthorizationProvider(currentUserService);
  private SaTokenDao previousDao;
  private StpInterface previousInterface;
  private SaTokenContext previousContext;

  @BeforeEach
  void setUp() {
    previousDao = SaManager.getSaTokenDao();
    previousInterface = SaManager.getStpInterface();
    previousContext = SaManager.getSaTokenContext();
    SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
    SaManager.setStpInterface(provider);
    SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
  }

  @AfterEach
  void tearDown() {
    SaTokenContextMockUtil.clearContext();
    SaManager.setSaTokenContext(previousContext);
    SaManager.setStpInterface(previousInterface);
    SaManager.setSaTokenDao(previousDao);
  }

  @Test
  void exposesDatabaseRoleAndNoPermissionCodes() {
    UUID adminId = UUID.randomUUID();
    User admin = user(adminId, UserRole.ADMIN);
    when(currentUserService.require(adminId.toString())).thenReturn(admin);

    assertEquals(List.of("ADMIN"), provider.getRoleList(adminId.toString(), "login"));
    assertEquals(List.of(), provider.getPermissionList(adminId.toString(), "login"));
  }

  @Test
  void letsAdminPassAndRejectsRegularUserThroughStpUtil() {
    UUID adminId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(currentUserService.require(adminId.toString())).thenReturn(user(adminId, UserRole.ADMIN));
    when(currentUserService.require(userId.toString())).thenReturn(user(userId, UserRole.USER));

    SaTokenContextMockUtil.setMockContext(
        () -> {
          StpUtil.login(adminId.toString());
          assertDoesNotThrow(() -> StpUtil.checkRole("ADMIN"));
          StpUtil.logout();
          StpUtil.login(userId.toString());
          assertThrows(NotRoleException.class, () -> StpUtil.checkRole("ADMIN"));
        });
  }

  @Test
  void rejectsRoleCheckWhenNotLoggedIn() {
    SaTokenContextMockUtil.setMockContext(
        () -> assertThrows(NotLoginException.class, () -> StpUtil.checkRole("ADMIN")));
  }

  private User user(UUID id, UserRole role) {
    User user = new User();
    user.setId(id);
    user.setRole(role);
    user.setEnabled(true);
    return user;
  }
}
