package com.hnu.backend.configuration;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class LocalOnly implements ApplicationRunner {
  private final Environment environment;

  public LocalOnly(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!environment.acceptsProfiles(Profiles.of("local"))) {
      throw new IllegalStateException(
          "Stage 1 has no authentication; only the local profile is supported");
    }
  }
}
