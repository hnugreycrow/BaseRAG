package com.hnu.backend.rag.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 为 RAG 领域对象创建不可变的 JSON 兼容值。 */
public final class JsonValues {
  private JsonValues() {}

  /**
   * 深度复制 JSON 对象，并冻结其中嵌套的对象和数组。
   *
   * @param source 只包含字符串键和 JSON 兼容值的对象
   * @return 保持字段顺序的不可变副本
   */
  public static Map<String, Object> immutableObject(Map<String, ?> source) {
    LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
    source.forEach((key, value) -> copy.put(key, immutableValue(value)));
    return Collections.unmodifiableMap(copy);
  }

  private static Object immutableValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
      map.forEach((key, nested) -> copy.put(String.valueOf(key), immutableValue(nested)));
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      list.forEach(item -> copy.add(immutableValue(item)));
      return Collections.unmodifiableList(copy);
    }
    return value;
  }
}
