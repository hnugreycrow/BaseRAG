package com.hnu.backend.model.client;

import java.util.Map;

/** 把统一的思考开关转换为供应商请求参数。 */
public interface ThinkingParameterAdapter {
  String provider();

  void apply(Map<String, Object> payload, boolean enabled);
}
