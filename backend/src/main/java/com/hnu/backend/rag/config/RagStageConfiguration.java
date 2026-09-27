package com.hnu.backend.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 保留旧配置键，向算法提供所需配置视图。 */
@Configuration
public class RagStageConfiguration {
  /** 装配 Planning 所需配置。 */
  @Bean
  public RagStageSettings.Planning planningSettings(RagProperties config) {
    return RagStageSettings.planning(config);
  }

  /** 装配 Routing 所需配置。 */
  @Bean
  public RagStageSettings.Routing routingSettings(RagProperties config) {
    return RagStageSettings.routing(config);
  }

  /** 装配 Tools 所需配置。 */
  @Bean
  public RagStageSettings.Tools toolsSettings(RagProperties config) {
    return RagStageSettings.tools(config);
  }

  /** 装配 Retrieval 所需配置。 */
  @Bean
  public RagStageSettings.Retrieval retrievalSettings(RagProperties config) {
    return RagStageSettings.retrieval(config);
  }

  /** 装配 Execution 所需配置。 */
  @Bean
  public RagStageSettings.Execution executionSettings(RagProperties config) {
    return RagStageSettings.execution(config);
  }
}
