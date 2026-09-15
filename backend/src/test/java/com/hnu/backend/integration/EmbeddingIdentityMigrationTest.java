package com.hnu.backend.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 在独立测试数据库的临时表上验证旧向量绑定迁移；事务始终回滚。 */
@EnabledIfEnvironmentVariable(named = "RAG_INTEGRATION", matches = "true")
class EmbeddingIdentityMigrationTest {
  @Test
  void backfillsKnownLegacyBindingAndKeepsUnboundKnowledgeBaseEmpty() throws Exception {
    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        tables(statement);
        statement.executeUpdate(
            "INSERT INTO knowledge_bases VALUES "
                + "('00000000-0000-0000-0000-000000000001', 'Qwen/Qwen3-Embedding-8B', 1536), "
                + "('00000000-0000-0000-0000-000000000002', NULL, NULL)");
        statement.executeUpdate(
            "INSERT INTO document_versions "
                + "SELECT '00000000-0000-0000-0000-000000000001', "
                + "'Qwen/Qwen3-Embedding-8B', 1536 FROM generate_series(1, 5)");
        statement.execute(script());
        try (ResultSet result =
            statement.executeQuery(
                "SELECT embedding_model_id, embedding_provider FROM knowledge_bases "
                    + "WHERE embedding_model IS NOT NULL")) {
          assertTrue(result.next());
          assertEquals("qwen-emb-8b", result.getString(1));
          assertEquals("siliconflow", result.getString(2));
        }
        try (ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM document_versions "
                    + "WHERE embedding_model_id = 'qwen-emb-8b' "
                    + "AND embedding_provider = 'siliconflow'")) {
          assertTrue(result.next());
          assertEquals(5, result.getInt(1));
        }
        try (ResultSet result =
            statement.executeQuery(
                "SELECT embedding_model_id, embedding_provider FROM knowledge_bases "
                    + "WHERE embedding_model IS NULL")) {
          assertTrue(result.next());
          assertNull(result.getString(1));
          assertNull(result.getString(2));
        }
        assertThrows(
            SQLException.class,
            () ->
                statement.executeUpdate(
                    "INSERT INTO document_versions "
                        + "(knowledge_base_id, embedding_model, embedding_dimensions, "
                        + "embedding_model_id, embedding_provider) VALUES "
                        + "('00000000-0000-0000-0000-000000000001', "
                        + "'Qwen/Qwen3-Embedding-8B', 1536, 'other-id', 'other-provider')"));
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  void unknownLegacyCombinationFailsWithTheCombinationInTheError() throws Exception {
    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        tables(statement);
        statement.executeUpdate(
            "INSERT INTO knowledge_bases VALUES "
                + "('00000000-0000-0000-0000-000000000003', 'unknown-model', 1024)");
        SQLException error = assertThrows(SQLException.class, () -> statement.execute(script()));
        assertTrue(error.getMessage().contains("unknown-model/1024"));
      } finally {
        connection.rollback();
      }
    }
  }

  private void tables(Statement statement) throws SQLException {
    statement.execute(
        "CREATE TEMP TABLE knowledge_bases "
            + "(id uuid PRIMARY KEY, embedding_model text, embedding_dimensions integer)");
    statement.execute(
        "CREATE TEMP TABLE document_versions "
            + "(knowledge_base_id uuid, embedding_model text, embedding_dimensions integer)");
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection(
        environment("TEST_DB_URL", "jdbc:postgresql://localhost:5432/baserag_test"),
        environment("POSTGRES_USER", "baserag"),
        environment("POSTGRES_PASSWORD", "baserag-local-change-me"));
  }

  private String environment(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  private String script() throws Exception {
    try (var input = getClass().getResourceAsStream("/db/migration/V6__embedding_identity.sql")) {
      assertNotNull(input);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
