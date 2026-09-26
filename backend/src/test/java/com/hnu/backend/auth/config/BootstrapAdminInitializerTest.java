package com.hnu.backend.auth.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.entity.UserRole;
import com.hnu.backend.auth.mapper.UserMapper;
import com.hnu.backend.auth.service.AccountPolicy;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class BootstrapAdminInitializerTest {
  @Test
  void initializesAdministratorWithoutCreatingKnowledgeBase() {
    BootstrapAdminProperties properties = new BootstrapAdminProperties();
    properties.setUsername(" Admin.User ");
    properties.setDisplayName(" 首次管理员 ");
    properties.setPassword("StrongPass123!");
    UserMapper userMapper = mock(UserMapper.class);
    PasswordEncoder passwords = mock(PasswordEncoder.class);
    TransactionTemplate tx = mock(TransactionTemplate.class);
    AtomicReference<User> inserted = new AtomicReference<>();
    when(userMapper.countRealUsers()).thenReturn(0L);
    when(userMapper.lockLegacyOwner()).thenReturn(new User());
    when(passwords.encode("StrongPass123!")).thenReturn("bcrypt-hash");
    when(userMapper.insert(any(User.class)))
        .thenAnswer(
            invocation -> {
              inserted.set(invocation.getArgument(0));
              return 1;
            });
    org.mockito.Mockito.doAnswer(
            invocation -> {
              Consumer<TransactionStatus> callback = invocation.getArgument(0);
              callback.accept(mock(TransactionStatus.class));
              return null;
            })
        .when(tx)
        .executeWithoutResult(any());
    BootstrapAdminInitializer initializer =
        new BootstrapAdminInitializer(properties, userMapper, new AccountPolicy(), passwords, tx);

    initializer.run(mock(ApplicationArguments.class));

    User admin = inserted.get();
    assertEquals("admin.user", admin.getUsername());
    assertEquals("首次管理员", admin.getDisplayName());
    assertEquals(UserRole.ADMIN, admin.getRole());
    assertEquals("bcrypt-hash", admin.getPasswordHash());
    verify(userMapper).transferKnowledgeBases(admin.getId());
    verify(userMapper).transferConversations(admin.getId());
    verify(userMapper).deleteLegacyOwner();
  }
}
