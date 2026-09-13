package com.hnu.backend.configuration;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "conversation")
public class ConversationProperties {
  private int recentTurns = 8;
  private int summaryBatchTurns = 4;
  private int checkpointChars = 512;
  private long checkpointIntervalMs = 1_000;

  @PostConstruct
  void validate() {
    if (recentTurns < 1
        || summaryBatchTurns < 1
        || checkpointChars < 1
        || checkpointIntervalMs < 100) {
      throw new IllegalArgumentException("Invalid conversation configuration");
    }
  }
}
