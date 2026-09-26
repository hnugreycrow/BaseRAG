package com.hnu.backend.document.configuration;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 文档分块的同步并发数与进程内异步队列容量。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "document.processing")
public class DocumentProcessingProperties {
  /** 同步分块同时执行的文档数。 */
  private int syncConcurrency = 2;

  /** 异步分块同时执行的文档数。 */
  private int workers = 2;

  /** 异步分块等待队列中的文档数上限。 */
  private int queueCapacity = 50;

  /** 拒绝无法创建有界队列的配置。 */
  @PostConstruct
  void validate() {
    if (syncConcurrency < 1
        || workers < 1
        || queueCapacity < 1
        || workers > Integer.MAX_VALUE - queueCapacity) {
      throw new IllegalArgumentException("Invalid document processing configuration");
    }
  }
}
