package com.hnu.backend.configuration;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 会话记忆、摘要和流式检查点相关配置。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "conversation")
public class ConversationProperties {
  /** 每次构造上下文时保留的最近完整轮次数。 */
  private int recentTurns = 8;

  /** 摘要与最近原文重叠的轮次数，也是后续更新的最大批次大小。 */
  private int summaryBatchTurns = 4;

  /** 话题摘要的 Unicode 字符上限。 */
  private int summaryMaxChars = 400;

  /** 流式内容累计到该字符数时触发持久化检查点。 */
  private int checkpointChars = 512;

  /** 两次流式检查点之间的最短时间间隔（毫秒）。 */
  private long checkpointIntervalMs = 1_000;

  /** 校验会话配置，避免无效值导致上下文丢失或过于频繁地写库。 */
  @PostConstruct
  void validate() {
    if (recentTurns < 1
        || summaryBatchTurns < 1
        || summaryBatchTurns >= recentTurns
        || summaryMaxChars < 1
        || checkpointChars < 1
        || checkpointIntervalMs < 100) {
      throw new IllegalArgumentException("Invalid conversation configuration");
    }
  }
}
