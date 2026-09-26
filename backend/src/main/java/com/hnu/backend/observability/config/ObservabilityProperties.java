package com.hnu.backend.observability.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 单次问答 Trace 的保留和清理配置。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {
  /** Trace 默认保留天数。 */
  private int retentionDays = 30;

  /** 是否在问题规划日志中输出用户问题原文。 */
  private boolean logQuestionContent = false;

  /** 每日清理任务的 Spring cron 表达式。 */
  private String cleanupCron = "0 15 3 * * *";

  /** 校验保留期和清理表达式不为空。 */
  @PostConstruct
  void validate() {
    if (retentionDays < 1 || cleanupCron == null || cleanupCron.isBlank()) {
      throw new IllegalArgumentException("Invalid observability configuration");
    }
  }
}
