package com.hnu.backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.mapper.UserMapper;
import com.hnu.backend.common.exception.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CurrentUserServiceTest {
  private final UserMapper userMapper = mock(UserMapper.class);
  private final CurrentUserService currentUserService = new CurrentUserService(userMapper);

  @Test
  void rejectsMalformedLoginIdAsUnauthenticated() {
    try (var stp = mockStatic(StpUtil.class)) {
      ApiException error =
          assertThrows(ApiException.class, () -> currentUserService.require("not-a-uuid"));

      assertEquals("AUTH_REQUIRED", error.code());
      assertEquals(HttpStatus.UNAUTHORIZED, error.status());
      stp.verify(() -> StpUtil.logout());
      verifyNoInteractions(userMapper);
    }
  }

  @Test
  void rejectsDeletedAccountAsUnauthenticated() {
    UUID userId = UUID.randomUUID();
    when(userMapper.find(userId)).thenReturn(null);

    try (var stp = mockStatic(StpUtil.class)) {
      ApiException error =
          assertThrows(ApiException.class, () -> currentUserService.require(userId.toString()));

      assertEquals("AUTH_REQUIRED", error.code());
      assertEquals(HttpStatus.UNAUTHORIZED, error.status());
      stp.verify(() -> StpUtil.logout(userId.toString()));
    }
  }

  @Test
  void rejectsDisabledAccountAndRevokesAllSessions() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    user.setEnabled(false);
    when(userMapper.find(userId)).thenReturn(user);

    try (var stp = mockStatic(StpUtil.class)) {
      ApiException error =
          assertThrows(ApiException.class, () -> currentUserService.require(userId.toString()));

      assertEquals("ACCOUNT_DISABLED", error.code());
      assertEquals(HttpStatus.FORBIDDEN, error.status());
      stp.verify(() -> StpUtil.logout(userId.toString()));
    }
  }
}
