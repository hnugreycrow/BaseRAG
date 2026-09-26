package com.hnu.backend.shared.json;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/** 按对外协议、历史快照和模型交互集中维护不可变 JSON 配置。 */
public final class JsonCodecs {
  private static final JsonMapper PROTOCOL = configureProtocol(JsonMapper.builder()).build();
  private static final JsonMapper SNAPSHOTS =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();
  private static final JsonMapper MODELS =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private JsonCodecs() {}

  /** 返回协议编解码器；时间使用 ISO 字符串，空值是否省略由载荷类型注解决定。 */
  public static JsonMapper protocol() {
    return PROTOCOL;
  }

  /** 返回快照编解码器；容忍同版本新增字段，缺省字段与版本迁移由对应解码器处理。 */
  public static JsonMapper snapshots() {
    return SNAPSHOTS;
  }

  /** 返回模型交互编解码器；完整 JSON 后的额外内容视为错误，业务结构仍由各阶段校验。 */
  public static JsonMapper models() {
    return MODELS;
  }

  /**
   * 将相同协议规则应用到 Spring 管理的 HTTP/SSE 编解码器。
   *
   * @param builder 保留 Spring 自动注册模块的构建器
   * @return 配置后的原构建器
   */
  public static JsonMapper.Builder configureProtocol(JsonMapper.Builder builder) {
    return builder
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
  }
}
