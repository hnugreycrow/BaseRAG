package com.hnu.backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.entity.UserRole;
import com.hnu.backend.auth.mapper.UserMapper;
import com.hnu.backend.common.exception.ApiException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class AdminUserServiceTest {
  private final UserMapper userMapper = mock(UserMapper.class);
  private final PasswordEncoder passwords = mock(PasswordEncoder.class);
  private final SessionRevocationService sessionRevocationService =
      mock(SessionRevocationService.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private final AdminUserService adminUserService =
      new AdminUserService(
          userMapper, new AccountPolicy(), passwords, sessionRevocationService, tx);

  @BeforeEach
  void runTransactionsImmediately() {
    when(tx.execute(any()))
        .thenAnswer(
            invocation -> {
              TransactionCallback<?> callback = invocation.getArgument(0);
              return callback.doInTransaction(mock(TransactionStatus.class));
            });
  }

  @Test
  void createsUserWithoutCreatingKnowledgeBase() {
    AtomicReference<User> inserted = new AtomicReference<>();
    when(passwords.encode("StrongPass123!")).thenReturn("bcrypt-hash");
    when(userMapper.insert(any(User.class)))
        .thenAnswer(
            invocation -> {
              inserted.set(invocation.getArgument(0));
              return 1;
            });
    when(userMapper.find(any(UUID.class))).thenAnswer(ignored -> inserted.get());

    var response = adminUserService.create(" New.User ", " 新用户 ", "StrongPass123!", UserRole.USER);

    assertEquals("new.user", response.username());
    assertEquals("新用户", response.displayName());
    assertEquals(UserRole.USER, response.role());
    assertEquals("bcrypt-hash", inserted.get().getPasswordHash());
    verify(userMapper).insert(inserted.get());
  }

  @Test
  void rejectsMissingCreateTransactionResult() {
    doReturn(null).when(tx).execute(any());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> adminUserService.create("new.user", "新用户", "StrongPass123!", UserRole.USER));

    assertEquals("创建用户事务未返回结果", error.getMessage());
    verifyNoInteractions(userMapper);
  }

  @Test
  void rejectsMissingStatusTransactionResult() {
    doReturn(null).when(tx).execute(any());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> adminUserService.setEnabled(UUID.randomUUID(), UUID.randomUUID(), true));

    assertEquals("更新用户状态事务未返回结果", error.getMessage());
    verifyNoInteractions(userMapper);
  }

  @Test
  void rejectsDisablingTheCurrentAdministrator() {
    UUID actorId = UUID.randomUUID();

    ApiException error =
        assertThrows(
            ApiException.class, () -> adminUserService.setEnabled(actorId, actorId, false));

    assertEquals("SELF_DISABLE_NOT_ALLOWED", error.code());
    verifyNoInteractions(userMapper, sessionRevocationService);
  }

  @Test
  void locksAdministratorsBeforeRejectingTheLastEnabledAdministrator() {
    UUID actorId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    User target = user(targetId, UserRole.ADMIN, true);
    when(userMapper.find(targetId)).thenReturn(target);
    when(userMapper.lockEnabledAdmins()).thenReturn(List.of(target));
    when(userMapper.countEnabledAdmins()).thenReturn(1L);

    ApiException error =
        assertThrows(
            ApiException.class, () -> adminUserService.setEnabled(actorId, targetId, false));

    assertEquals("LAST_ADMIN_REQUIRED", error.code());
    var order = inOrder(userMapper);
    order.verify(userMapper).find(targetId);
    order.verify(userMapper).lockEnabledAdmins();
    order.verify(userMapper).countEnabledAdmins();
    verifyNoInteractions(sessionRevocationService);
  }

  private User user(UUID id, UserRole role, boolean enabled) {
    User user = new User();
    user.setId(id);
    user.setUsername("admin");
    user.setDisplayName("管理员");
    user.setRole(role);
    user.setEnabled(enabled);
    return user;
  }
}
