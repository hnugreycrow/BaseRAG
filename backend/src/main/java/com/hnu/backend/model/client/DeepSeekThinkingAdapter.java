package com.hnu.backend.model.client;

import java.util.Map;
import org.springframework.stereotype.Component;

/** 将思考开关映射为 DeepSeek 请求中的 {@code thinking.type} 参数。 */
@Component
public class DeepSeekThinkingAdapter implements ThinkingParameterAdapter {
  @Override
  public String provider() {
    return "deepseek";
  }

  @Override
  public void apply(Map<String, Object> payload, boolean enabled) {
    payload.put("thinking", Map.of("type", enabled ? "enabled" : "disabled"));
  }
}
