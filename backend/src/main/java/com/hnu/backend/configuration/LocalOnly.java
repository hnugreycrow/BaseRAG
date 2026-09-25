package com.hnu.backend.configuration;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** 启动时限制当前无认证版本只能运行于 local 配置环境。 */
@Component
public class LocalOnly implements ApplicationRunner {
  private final Environment environment;

  /**
   * 创建本地环境守卫。
   *
   * @param environment Spring 运行环境
   */
  public LocalOnly(Environment environment) {
    this.environment = environment;
  }

  /** {@inheritDoc} */
  @Override
  public void run(@NonNull ApplicationArguments args) {
    if (!environment.acceptsProfiles(Profiles.of("local"))) {
      throw new IllegalStateException(
          "Stage 1 has no authentication; only the local profile is supported");
    }
  }
}
