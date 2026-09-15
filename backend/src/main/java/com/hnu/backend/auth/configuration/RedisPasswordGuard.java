package com.hnu.backend.auth.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 启动时拒绝未配置密码的 Redis，避免认证存储以无认证模式运行。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RedisPasswordGuard implements ApplicationRunner {
  private final String password;

  /**
   * 创建 Redis 密码配置守卫。
   *
   * @param password 当前 Redis 连接密码
   */
  public RedisPasswordGuard(@Value("${spring.data.redis.password:}") String password) {
    this.password = password;
  }

  /**
   * 验证 Redis 密码非空；认证会话不允许连接无密码 Redis。
   *
   * @param args 应用启动参数；本守卫不使用
   * @throws IllegalStateException Redis 密码缺失时抛出
   */
  @Override
  public void run(ApplicationArguments args) {
    if (password == null || password.isBlank()) {
      throw new IllegalStateException("必须通过 REDIS_PASSWORD 配置非空 Redis 密码");
    }
  }
}
