package com.hnu.backend.model.client;

import java.util.Map;
import org.springframework.stereotype.Component;

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
