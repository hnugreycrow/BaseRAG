package com.hnu.backend.auth.configuration;

import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.entity.UserRole;
import com.hnu.backend.auth.mapper.UserMapper;
import com.hnu.backend.auth.service.AccountPolicy;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 首次启动时从环境变量创建管理员并接管迁移前数据。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BootstrapAdminInitializer implements ApplicationRunner {
  private final BootstrapAdminProperties properties;
  private final UserMapper userMapper;
  private final AccountPolicy policy;
  private final PasswordEncoder passwords;
  private final TransactionTemplate tx;

  /**
   * 创建首次管理员初始化器。
   *
   * @param properties 环境变量映射
   * @param userMapper 用户数据访问接口
   * @param policy 账号字段策略
   * @param passwords BCrypt 编码器
   * @param tx 事务模板
   */
  public BootstrapAdminInitializer(
      BootstrapAdminProperties properties,
      UserMapper userMapper,
      AccountPolicy policy,
      PasswordEncoder passwords,
      TransactionTemplate tx) {
    this.properties = properties;
    this.userMapper = userMapper;
    this.policy = policy;
    this.passwords = passwords;
    this.tx = tx;
  }

  /**
   * 数据库尚无真实用户时执行一次性初始化。
   *
   * @param args 应用启动参数；本初始化器不使用
   */
  @Override
  public void run(@NonNull ApplicationArguments args) {
    if (userMapper.countRealUsers() > 0) return;
    try {
      tx.executeWithoutResult(ignored -> initialize());
    } catch (RuntimeException error) {
      throw new IllegalStateException(
          "首次启动必须配置合法的 BASERAG_ADMIN_USERNAME、BASERAG_ADMIN_DISPLAY_NAME 和 BASERAG_ADMIN_PASSWORD",
          error);
    }
  }

  /** 在锁定迁移占位行的事务中创建管理员并转交历史数据。 */
  private void initialize() {
    if (userMapper.lockLegacyOwner() == null) {
      throw new IllegalStateException("遗留所有者不存在，数据库迁移状态不完整");
    }
    if (userMapper.countRealUsers() > 0) return;
    String username = policy.username(properties.getUsername());
    String displayName = policy.displayName(properties.getDisplayName());
    String password = policy.password(properties.getPassword());
    User admin = new User();
    admin.setId(UUID.randomUUID());
    admin.setUsername(username);
    admin.setDisplayName(displayName);
    admin.setPasswordHash(passwords.encode(password));
    admin.setRole(UserRole.ADMIN);
    admin.setEnabled(true);
    userMapper.insert(admin);
    userMapper.transferKnowledgeBases(admin.getId());
    userMapper.transferConversations(admin.getId());
    userMapper.deleteLegacyOwner();
  }
}
