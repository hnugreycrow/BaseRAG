package com.hnu.backend.observability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hnu.backend.observability.entity.RagRun;
import com.hnu.backend.observability.entity.RagStageRun;
import com.hnu.backend.observability.vo.RagRunResponses;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RagObservabilityPrivacyTest {
  private static final Set<String> FORBIDDEN_FIELDS =
      Set.of(
          "question",
          "answer",
          "content",
          "prompt",
          "cookie",
          "token",
          "apiKey",
          "secret",
          "sourceText",
          "evidenceText",
          "authorization");

  @Test
  void persistenceAndResponseTypesContainOnlySafeMetadata() throws Exception {
    assertSafeFields(
        Arrays.stream(RagRun.class.getDeclaredFields())
            .filter(field -> !field.isSynthetic())
            .map(java.lang.reflect.Field::getName)
            .collect(Collectors.toSet()));
    assertSafeFields(
        Arrays.stream(RagStageRun.class.getDeclaredFields())
            .filter(field -> !field.isSynthetic())
            .map(java.lang.reflect.Field::getName)
            .collect(Collectors.toSet()));
    assertSafeFields(componentNames(RagRunResponses.Summary.class));
    assertSafeFields(componentNames(RagRunResponses.Stage.class));

    try (InputStream input =
        getClass().getClassLoader().getResourceAsStream("db/migration/V5__rag_observability.sql")) {
      assertNotNull(input);
      String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
      for (String forbidden :
          Set.of(
              "question_text",
              "answer_text",
              "source_text",
              "evidence_text",
              "api_key",
              "access_token",
              "authorization",
              "cookie")) {
        assertFalse(migration.contains(forbidden), () -> "sensitive column: " + forbidden);
      }
    }
  }

  private Set<String> componentNames(Class<?> type) {
    return Arrays.stream(type.getRecordComponents())
        .map(java.lang.reflect.RecordComponent::getName)
        .collect(Collectors.toSet());
  }

  private void assertSafeFields(Set<String> fields) {
    for (String forbidden : FORBIDDEN_FIELDS) {
      assertFalse(fields.contains(forbidden), () -> "sensitive field: " + forbidden);
    }
  }
}
