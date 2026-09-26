package com.hnu.backend.config;

import com.hnu.backend.common.json.JsonCodecs;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 将统一协议配置接入 Spring 的 HTTP 与 SSE 消息转换器。 */
@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {
  /** 返回协议配置定制器，不替换 Spring 自动发现的模块和序列化器。 */
  @Bean
  JsonMapperBuilderCustomizer protocolJsonCustomizer() {
    return JsonCodecs::configureProtocol;
  }
}
