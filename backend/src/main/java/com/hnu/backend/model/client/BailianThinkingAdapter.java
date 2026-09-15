package com.hnu.backend.model.client;

import java.util.Map;
import org.springframework.stereotype.Component;

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
