package com.hnu.backend.model.client;

import java.util.Map;
import org.springframework.stereotype.Component;

/** 将思考开关映射为百炼请求中的 {@code enable_thinking} 参数。 */
@Component
public class BailianThinkingAdapter implements ThinkingParameterAdapter {
  @Override
  public String provider() {
    return "bailian";
  }

  @Override
  public void apply(Map<String, Object> payload, boolean enabled) {
    payload.put("enable_thinking", enabled);
  }
}
