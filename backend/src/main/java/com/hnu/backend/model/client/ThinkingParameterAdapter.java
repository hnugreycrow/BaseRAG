package com.hnu.backend.model.client;

import java.util.Map;

/** 把统一的思考开关转换为供应商请求参数。 */
public interface ThinkingParameterAdapter {
  /**
   * 返回适配器对应的供应商配置标识。
   *
   * @return 用于选择适配器的供应商标识
   */
  String provider();

  /**
   * 将统一思考开关写入供应商请求体。
   *
   * @param payload 待发送的可变请求参数；方法会直接修改它
   * @param enabled 是否启用思考
   */
  void apply(Map<String, Object> payload, boolean enabled);
}
