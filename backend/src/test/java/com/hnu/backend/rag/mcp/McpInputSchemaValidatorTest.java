package com.hnu.backend.rag.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpInputSchemaValidatorTest {
  private final McpInputSchemaValidator validator = new McpInputSchemaValidator();

  @Test
  void acceptsOnlyDeclaredArgumentsWithAllRequiredNames() {
    Map<String, Object> schema =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("name", Map.of("type", "string")),
            "required",
            List.of("name"),
            "additionalProperties",
            false);
    validator.validateSchema(schema);

    assertTrue(validator.accepts(schema, Map.of("name", "Alice")));
    assertFalse(validator.accepts(schema, Map.of()));
    assertFalse(validator.accepts(schema, Map.of("name", "Alice", "extra", "value")));
  }

  @Test
  void rejectsRequiredNamesWithWrongTypeOrMissingProperty() {
    Map<String, Object> base =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("name", Map.of("type", "string")),
            "additionalProperties",
            false);
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.validateSchema(withRequired(base, List.of(42))));
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.validateSchema(withRequired(base, List.of("missing"))));
  }

  private Map<String, Object> withRequired(Map<String, Object> base, List<?> required) {
    return Map.of(
        "type", base.get("type"),
        "properties", base.get("properties"),
        "additionalProperties", base.get("additionalProperties"),
        "required", required);
  }
}
