package com.hnu.backend.rag.mcp;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 校验 MCP 工具使用的受限 JSON Schema。
 *
 * <p>首版只接受关闭额外字段的对象 Schema 和常用标量约束。无法识别的关键字在注册阶段直接报错，避免产生虚假的安全边界。
 */
@Component
public class McpInputSchemaValidator {
  private static final Set<String> ROOT_KEYS =
      Set.of("type", "properties", "required", "additionalProperties");
  private static final Set<String> PROPERTY_KEYS =
      Set.of("type", "enum", "minLength", "maxLength", "minimum", "maximum");
  private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean");

  /** 在应用启动注册工具时检查 Schema 本身是否属于受支持且封闭的子集。 */
  public void validateSchema(Map<String, Object> schema) {
    requireOnlyKeys(schema, ROOT_KEYS, "root");
    if (!"object".equals(schema.get("type"))) {
      throw new IllegalArgumentException("MCP input schema root type must be object");
    }
    if (!(schema.get("properties") instanceof Map<?, ?> properties)) {
      throw new IllegalArgumentException("MCP input schema properties must be an object");
    }
    if (!Boolean.FALSE.equals(schema.get("additionalProperties"))) {
      throw new IllegalArgumentException("MCP input schema must disable additional properties");
    }

    Set<String> propertyNames = new HashSet<>();
    properties.forEach(
        (name, definition) -> {
          if (!(name instanceof String propertyName)
              || propertyName.isBlank()
              || !(definition instanceof Map<?, ?> propertySchema)) {
            throw new IllegalArgumentException("Invalid MCP input property definition");
          }
          propertyNames.add(propertyName);
          validatePropertySchema(propertySchema);
        });

    Object requiredValue = schema.getOrDefault("required", List.of());
    if (!(requiredValue instanceof List<?> required)
        || required.stream().anyMatch(name -> !(name instanceof String))) {
      throw new IllegalArgumentException("MCP input schema required must be a string array");
    }
    List<String> requiredNames = required.stream().map(String.class::cast).toList();
    if (!propertyNames.containsAll(requiredNames)) {
      throw new IllegalArgumentException("MCP input schema required references unknown property");
    }
  }

  /** 校验模型给出的参数，不执行类型转换或默认值填充。 */
  public boolean accepts(Map<String, Object> schema, Map<String, Object> arguments) {
    Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
    List<?> required = (List<?>) schema.getOrDefault("required", List.of());
    for (Object name : required) {
      if (!(name instanceof String propertyName) || !arguments.containsKey(propertyName)) {
        return false;
      }
    }
    for (String name : arguments.keySet()) {
      if (!properties.containsKey(name)) {
        return false;
      }
    }
    for (Map.Entry<String, Object> entry : arguments.entrySet()) {
      Map<?, ?> propertySchema = (Map<?, ?>) properties.get(entry.getKey());
      if (!acceptsValue(propertySchema, entry.getValue())) {
        return false;
      }
    }
    return true;
  }

  private void validatePropertySchema(Map<?, ?> schema) {
    requireOnlyKeys(schema, PROPERTY_KEYS, "property");
    Object type = schema.get("type");
    if (!(type instanceof String value) || !TYPES.contains(value)) {
      throw new IllegalArgumentException("Unsupported MCP input property type");
    }
    if (!"string".equals(value)
        && (schema.containsKey("minLength") || schema.containsKey("maxLength"))) {
      throw new IllegalArgumentException("MCP length constraints require a string property");
    }
    if (!("integer".equals(value) || "number".equals(value))
        && (schema.containsKey("minimum") || schema.containsKey("maximum"))) {
      throw new IllegalArgumentException("MCP range constraints require a numeric property");
    }
    validateNonNegativeInteger(schema.get("minLength"), "minLength");
    validateNonNegativeInteger(schema.get("maxLength"), "maxLength");
    if (schema.containsKey("minLength")
        && schema.containsKey("maxLength")
        && ((Number) schema.get("minLength")).longValue()
            > ((Number) schema.get("maxLength")).longValue()) {
      throw new IllegalArgumentException("MCP input minLength exceeds maxLength");
    }
    validateNumber(schema.get("minimum"), "minimum");
    validateNumber(schema.get("maximum"), "maximum");
    if (schema.containsKey("minimum")
        && schema.containsKey("maximum")
        && decimal((Number) schema.get("minimum"))
                .compareTo(decimal((Number) schema.get("maximum")))
            > 0) {
      throw new IllegalArgumentException("MCP input minimum exceeds maximum");
    }
    if (schema.containsKey("enum")
        && !(schema.get("enum") instanceof List<?> values && !values.isEmpty())) {
      throw new IllegalArgumentException("MCP input enum must be a non-empty array");
    }
    if (schema.get("enum") instanceof List<?> values
        && values.stream().anyMatch(item -> !matchesType(value, item))) {
      throw new IllegalArgumentException("MCP input enum contains a value of the wrong type");
    }
  }

  private boolean acceptsValue(Map<?, ?> schema, Object value) {
    if (value == null) {
      return false;
    }
    String type = (String) schema.get("type");
    if (!matchesType(type, value)) {
      return false;
    }
    if (schema.get("enum") instanceof List<?> values && !values.contains(value)) {
      return false;
    }
    if (value instanceof String text) {
      int length = text.codePointCount(0, text.length());
      if (schema.get("minLength") instanceof Number minimum && length < minimum.longValue()) {
        return false;
      }
      if (schema.get("maxLength") instanceof Number maximum && length > maximum.longValue()) {
        return false;
      }
    }
    if (value instanceof Number number) {
      BigDecimal decimal;
      try {
        decimal = decimal(number);
      } catch (NumberFormatException e) {
        return false;
      }
      if (schema.get("minimum") instanceof Number minimum
          && decimal.compareTo(new BigDecimal(minimum.toString())) < 0) {
        return false;
      }
      if (schema.get("maximum") instanceof Number maximum
          && decimal.compareTo(new BigDecimal(maximum.toString())) > 0) {
        return false;
      }
    }
    return true;
  }

  private void requireOnlyKeys(Map<?, ?> value, Set<String> allowed, String location) {
    if (value.keySet().stream()
        .anyMatch(key -> !(key instanceof String name) || !allowed.contains(name))) {
      throw new IllegalArgumentException("Unsupported MCP input schema keyword at " + location);
    }
  }

  private void validateNonNegativeInteger(Object value, String name) {
    if (value != null
        && (!(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long)
            || ((Number) value).longValue() < 0)) {
      throw new IllegalArgumentException("MCP input " + name + " must be a non-negative integer");
    }
  }

  private void validateNumber(Object value, String name) {
    if (value != null) {
      if (!(value instanceof Number number)) {
        throw new IllegalArgumentException("MCP input " + name + " must be a number");
      }
      try {
        decimal(number);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("MCP input " + name + " must be finite");
      }
    }
  }

  private boolean matchesType(String type, Object value) {
    return switch (type) {
      case "string" -> value instanceof String;
      case "integer" ->
          value instanceof Byte
              || value instanceof Short
              || value instanceof Integer
              || value instanceof Long;
      case "number" -> value instanceof Number;
      case "boolean" -> value instanceof Boolean;
      default -> false;
    };
  }

  private BigDecimal decimal(Number value) {
    return new BigDecimal(value.toString());
  }
}
