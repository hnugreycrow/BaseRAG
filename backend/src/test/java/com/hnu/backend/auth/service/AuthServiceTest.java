package com.hnu.backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.entity.UserRole;
import com.hnu.backend.auth.mapper.UserMapper;
import com.hnu.backend.shared.error.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

class AuthServiceTest {
  private final UserMapper userMapper = mock(UserMapper.class);
  private final PasswordEncoder passwords = mock(PasswordEncoder.class);
  private final LoginRateLimiter limiter = mock(LoginRateLimiter.class);
  private final CsrfTokenService csrfTokenService = mock(CsrfTokenService.class);
  private final CurrentUserService currentUserService = mock(CurrentUserService.class);
  private final SessionRevocationService sessionRevocationService =
      mock(SessionRevocationService.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private AuthService authService;

  @BeforeEach
  void setUp() {
    when(passwords.encode("dummy-password-used-for-timing-only")).thenReturn("dummy-hash");
    authService =
        new AuthService(
            userMapper,
            new AccountPolicy(),
            passwords,
            limiter,
            csrfTokenService,
            currentUserService,
            sessionRevocationService,
            tx);
  }

  @Test
  void unknownUserRunsDummyBcryptAndRecordsFailure() {
    when(userMapper.findByUsername("alice")).thenReturn(null);
    when(passwords.matches("wrong-password", "dummy-hash")).thenReturn(false);

    ApiException error =
        assertThrows(
            ApiException.class, () -> authService.login(" Alice ", "wrong-password", "127.0.0.1"));

    assertEquals("INVALID_CREDENTIALS", error.code());
    verify(passwords).matches("wrong-password", "dummy-hash");
    verify(limiter).recordFailure("alice", "127.0.0.1");
  }

  @Test
  void unknownUserCannotAuthenticateEvenWhenDummyHashMatches() {
    when(passwords.matches("dummy-password-used-for-timing-only", "dummy-hash")).thenReturn(true);

    ApiException error =
        assertThrows(
            ApiException.class,
            () -> authService.login("alice", "dummy-password-used-for-timing-only", "127.0.0.1"));

    assertEquals("INVALID_CREDENTIALS", error.code());
    verify(passwords).matches("dummy-password-used-for-timing-only", "dummy-hash");
    verify(limiter).recordFailure("alice", "127.0.0.1");
    verifyNoInteractions(csrfTokenService);
  }

  @Test
  void disabledAccountDoesNotCreateSession() {
    User user = user(false);
    when(userMapper.findByUsername("alice")).thenReturn(user);
    when(passwords.matches("correct-password", user.getPasswordHash())).thenReturn(true);

    try (var stp = mockStatic(StpUtil.class)) {
      ApiException error =
          assertThrows(
              ApiException.class,
              () -> authService.login("alice", "correct-password", "127.0.0.1"));
      assertEquals("ACCOUNT_DISABLED", error.code());
      stp.verify(
          () -> StpUtil.login(eq(user.getId().toString()), any(SaLoginParameter.class)), never());
    }
  }

  @Test
  void successfulLoginClearsLimiterAndCreatesIndependentBrowserSession() {
    User user = user(true);
    when(userMapper.findByUsername("alice")).thenReturn(user);
    when(passwords.matches("correct-password", user.getPasswordHash())).thenReturn(true);
    when(csrfTokenService.issue()).thenReturn("nonce");

    try (var stp = mockStatic(StpUtil.class)) {
      var result = authService.login("Alice", "correct-password", "127.0.0.1");

      assertEquals(user.getId(), result.user().id());
      assertEquals("nonce", result.csrfToken());
      verify(limiter).clear("alice", "127.0.0.1");
      verify(userMapper).updateById(user);
      stp.verify(() -> StpUtil.login(eq(user.getId().toString()), any(SaLoginParameter.class)));
    }
  }

  private User user(boolean enabled) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("alice");
    user.setDisplayName("Alice");
    user.setPasswordHash("bcrypt-hash");
    user.setRole(UserRole.USER);
    user.setEnabled(enabled);
    return user;
  }
}
