package com.hnu.backend.auth.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.io.ClassPathResource;

class AuthConfigurationTest {
  @Test
  void configuresBrowserSessionCookieAndSessionTimeouts() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yaml"));
    Properties properties = yaml.getObject();
    assertNotNull(properties);

    assertEquals("28800", properties.getProperty("sa-token.timeout"));
    assertEquals("1800", properties.getProperty("sa-token.active-timeout"));
    assertEquals("false", properties.getProperty("sa-token.is-lasting-cookie"));
    assertEquals("false", properties.getProperty("sa-token.is-share"));
    assertEquals("true", properties.getProperty("sa-token.is-concurrent"));
    assertEquals("-1", properties.getProperty("sa-token.max-login-count"));
    assertEquals("true", properties.getProperty("sa-token.cookie.http-only"));
    assertEquals("Strict", properties.getProperty("sa-token.cookie.same-site"));
    assertEquals("/", properties.getProperty("sa-token.cookie.path"));
    assertFalse(Boolean.parseBoolean(properties.getProperty("sa-token.cookie.secure")));
    assertEquals("1s", properties.getProperty("spring.data.redis.connect-timeout"));
  }

  @Test
  void rejectsAnEmptyRedisPasswordAtStartup() {
    assertThrows(
        IllegalStateException.class,
        () -> new RedisPasswordGuard(" ").run(mock(ApplicationArguments.class)));
  }
}
