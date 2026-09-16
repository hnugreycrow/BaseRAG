package com.hnu.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hnu.backend.intent.IntentNode;
import com.hnu.backend.intent.IntentNodeRequest;
import com.hnu.backend.intent.IntentTreeService;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.model.client.EmbeddingClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** 在独立测试库验证 Flyway 表结构和 MyBatis-Plus 实际映射，测试事务回滚。 */
@EnabledIfEnvironmentVariable(named = "RAG_INTEGRATION", matches = "true")
@SpringBootTest(
    properties = {
      "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5432/baserag_test}",
      "spring.datasource.username=${POSTGRES_USER:baserag}",
      "spring.datasource.password=${POSTGRES_PASSWORD:baserag-local-change-me}",
      "spring.data.redis.password=${REDIS_PASSWORD:baserag-redis-local-change-me}",
      "baserag.bootstrap-admin.username=integration-admin",
      "baserag.bootstrap-admin.display-name=Integration Admin",
      "baserag.bootstrap-admin.password=integration-password",
      "ai.embedding.default-model=qwen-emb-8b",
      "ai.embedding.candidates[0].id=qwen-emb-8b",
      "ai.embedding.candidates[0].provider=siliconflow",
      "ai.embedding.candidates[0].model=Qwen/Qwen3-Embedding-8B",
      "ai.embedding.candidates[0].dimension=2",
      "rag.storage.access-key=${RUSTFS_ACCESS_KEY:baserag-local}",
      "rag.storage.secret-key=${RUSTFS_SECRET_KEY:baserag-local-secret-change-me}",
      "rag.storage.bucket=baserag-test"
    })
class IntentTreeIntegrationTest {
  @Autowired IntentTreeService intentTreeService;
  @MockitoBean ChatClient chat;
  @MockitoBean EmbeddingClient embedding;

  @Test
  @Transactional
  void createsHierarchyAndReadsItThroughMappers() {
    IntentNode root =
        intentTreeService.create(
            new IntentNodeRequest(
                null, "集成测试分类", "分类节点", List.of("测试问题"), null, null, List.of(), true, 0));
    IntentNode leaf =
        intentTreeService.create(
            new IntentNodeRequest(
                root.id(),
                "集成测试闲聊",
                "系统直答",
                List.of("你好"),
                IntentNode.Kind.SYSTEM,
                null,
                List.of(),
                true,
                0));

    assertTrue(
        intentTreeService.list().stream()
            .anyMatch(node -> node.id().equals(leaf.id()) && node.parentId().equals(root.id())));
    intentTreeService.update(
        leaf.id(),
        new IntentNodeRequest(
            root.id(), "集成测试中间层", "分类", List.of(), null, null, List.of(), true, 0));
    assertTrue(
        intentTreeService.list().stream()
            .anyMatch(node -> node.id().equals(leaf.id()) && node.kind() == null));
    IntentNode nested =
        intentTreeService.create(
            new IntentNodeRequest(
                leaf.id(),
                "集成测试三级闲聊",
                "系统直答",
                List.of("你好"),
                IntentNode.Kind.SYSTEM,
                null,
                List.of(),
                true,
                0));
    assertEquals(
        nested.id(),
        intentTreeService.activeLeaves().stream()
            .filter(node -> node.id().equals(nested.id()))
            .findFirst()
            .orElseThrow()
            .id());
  }
}
