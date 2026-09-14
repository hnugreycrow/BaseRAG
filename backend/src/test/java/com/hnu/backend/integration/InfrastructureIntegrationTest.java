package com.hnu.backend.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.document.entity.DocumentChunk;
import com.hnu.backend.document.mapper.DocumentChunkMapper;
import com.hnu.backend.document.mapper.DocumentMapper;
import com.hnu.backend.document.mapper.DocumentVersionMapper;
import com.hnu.backend.document.service.DocumentService;
import com.hnu.backend.knowledgebase.entity.KnowledgeBase;
import com.hnu.backend.knowledgebase.mapper.KnowledgeBaseMapper;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.model.client.EmbeddingClient;
import com.hnu.backend.rag.retrieval.EmbeddingBinding;
import com.hnu.backend.rag.retrieval.RetrievalMapper;
import com.hnu.backend.shared.error.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@EnabledIfEnvironmentVariable(named = "RAG_INTEGRATION", matches = "true")
@SpringBootTest(
    properties = {
      "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5432/baserag_test}",
      "spring.datasource.username=${POSTGRES_USER:baserag}",
      "spring.datasource.password=${POSTGRES_PASSWORD:baserag-local-change-me}",
      "ai.embedding.candidates[0].id=qwen-emb-8b",
      "ai.embedding.candidates[0].provider=siliconflow",
      "ai.embedding.candidates[0].model=Qwen/Qwen3-Embedding-8B",
      "ai.embedding.candidates[0].dimension=2",
      "rag.storage.access-key=${RUSTFS_ACCESS_KEY:baserag-local}",
      "rag.storage.secret-key=${RUSTFS_SECRET_KEY:baserag-local-secret-change-me}",
      "rag.storage.bucket=baserag-test"
    })
class InfrastructureIntegrationTest {
  @Autowired KnowledgeBaseMapper kbMapper;
  @Autowired DocumentMapper documentMapper;
  @Autowired DocumentVersionMapper versionMapper;
  @Autowired DocumentChunkMapper chunkMapper;
  @Autowired RetrievalMapper retrievalMapper;
  @Autowired ConversationMapper conversationMapper;
  @Autowired MessageMapper messageMapper;
  @Autowired DocumentService documents;
  @Autowired com.hnu.backend.configuration.RagProperties config;
  @MockitoBean EmbeddingClient embedding;
  @MockitoBean ChatClient chat;

  @Test
  void persistsConversationMessagesAndJsonAuditSnapshots() {
    UUID conversationId = UUID.randomUUID();
    conversationMapper.insert(conversationId, "持久化测试");
    var conversation = conversationMapper.find(conversationId);
    assertEquals("{}", conversation.getSummaryJson());
    assertTrue(
        conversationMapper.list("持久化", 10).stream()
            .anyMatch(item -> item.getId().equals(conversationId)));

    Message user = new Message();
    user.setId(UUID.randomUUID());
    user.setConversationId(conversationId);
    user.setClientRequestId(UUID.randomUUID());
    user.setRole("USER");
    user.setTurnIndex(1);
    user.setVariantIndex(0);
    user.setActive(true);
    user.setStatus("COMPLETED");
    user.setContent("问题");
    user.setSourcesJson("[]");
    user.setCitationsJson("[]");
    messageMapper.insert(user);

    Message assistant = new Message();
    assistant.setId(UUID.randomUUID());
    assistant.setConversationId(conversationId);
    assistant.setRole("ASSISTANT");
    assistant.setTurnIndex(1);
    assistant.setVariantIndex(1);
    assistant.setActive(true);
    assistant.setReplyToId(user.getId());
    assistant.setStatus("PENDING");
    assistant.setContent("");
    assistant.setSourcesJson("[]");
    assistant.setCitationsJson("[]");
    messageMapper.insert(assistant);
    messageMapper.prepare(assistant.getId(), "独立问题", "[{\"citationId\":\"S1\"}]");
    messageMapper.complete(
        assistant.getId(),
        "回答 [S1]",
        "[\"S1\"]",
        "{\"id\":\"test\",\"provider\":\"test\",\"model\":\"test\"}");

    var stored = messageMapper.find(assistant.getId());
    assertEquals("COMPLETED", stored.getStatus());
    assertTrue(stored.getSourcesJson().contains("S1"));
    assertTrue(stored.getCitationsJson().contains("S1"));
    assertTrue(stored.getModelInfoJson().contains("test"));
    assertEquals(2, messageMapper.list(conversationId).size());
    assertEquals(
        1,
        conversationMapper.updateSummary(
            conversationId, "{\"goalsAndTopics\":[]}", 1, conversation.getSummaryRevision()));
    conversationMapper.delete(conversationId);
    assertTrue(messageMapper.list(conversationId).isEmpty());
  }

  @Test
  void cancellationIsIdempotentAndCannotBeOverwrittenByLateGenerationWork() {
    UUID conversationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID generationId = UUID.randomUUID();
    conversationMapper.insert(conversationId, "取消状态测试");

    Message user = new Message();
    user.setId(userId);
    user.setConversationId(conversationId);
    user.setClientRequestId(UUID.randomUUID());
    user.setRole("USER");
    user.setTurnIndex(1);
    user.setVariantIndex(0);
    user.setActive(true);
    user.setStatus("COMPLETED");
    user.setContent("问题");
    user.setSourcesJson("[]");
    user.setCitationsJson("[]");
    messageMapper.insert(user);

    Message assistant = new Message();
    assistant.setId(generationId);
    assistant.setConversationId(conversationId);
    assistant.setRole("ASSISTANT");
    assistant.setTurnIndex(1);
    assistant.setVariantIndex(1);
    assistant.setActive(true);
    assistant.setReplyToId(userId);
    assistant.setStatus("PENDING");
    assistant.setContent("");
    assistant.setSourcesJson("[]");
    assistant.setCitationsJson("[]");
    messageMapper.insert(assistant);

    assertEquals(1, messageMapper.cancelRunning(generationId, conversationId, "部分回答"));
    assertEquals(0, messageMapper.cancelRunning(generationId, conversationId, "被覆盖"));
    assertEquals(0, messageMapper.markStreaming(generationId));
    assertEquals(
        0,
        messageMapper.complete(
            generationId,
            "迟到的完整回答",
            "[]",
            "{\"id\":\"late\",\"provider\":\"test\",\"model\":\"test\"}"));

    Message stored = messageMapper.find(generationId);
    assertEquals("CANCELLED", stored.getStatus());
    assertEquals("部分回答", stored.getContent());
    assertEquals("GENERATION_CANCELLED", stored.getErrorCode());
    conversationMapper.delete(conversationId);
  }

  @BeforeEach
  void testModels() {
    when(embedding.embed(anyString(), anyInt(), anyList()))
        .thenAnswer(
            invocation -> {
              List<String> inputs = invocation.getArgument(2);
              return inputs.stream()
                  .map(input -> input.contains("报销") ? new float[] {0, 1} : new float[] {1, 0})
                  .toList();
            });
    when(chat.generate(anyString(), anyString()))
        .thenReturn(new ChatClient.Generation("员工年假为五天。[S1]", "test-chat", "test", "test-chat"));
  }

  private UUID kb() {
    var kb = new KnowledgeBase();
    kb.setId(UUID.randomUUID());
    kb.setName("自动化测试");
    kb.setEmbeddingModel("Qwen/Qwen3-Embedding-8B");
    kb.setEmbeddingDimensions(2);
    kbMapper.insert(kb);
    return kb.getId();
  }

  private MockMultipartFile file(String name, String text) {
    return new MockMultipartFile(
        "file", name, "text/markdown", text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void importsRealStorageAndVectorsAnswersFromActualDatabaseText() {
    UUID kb = kb();
    String original = "# 年假\n员工年假为五天。";
    var imported = documents.upload(kb, file("手册.md", original));
    assertEquals("UPLOADED", imported.status());
    assertEquals(0, imported.chunkCount());
    imported = documents.createChunks(kb, imported.documentId());
    assertEquals("READY", imported.status());
    UUID importedDocumentId = imported.documentId();
    var document = documentMapper.selectById(importedDocumentId);
    assertNotNull(document.getActiveVersionId());
    var version = versionMapper.selectById(document.getActiveVersionId());
    var hits = retrievalMapper.search(kb, "[1,0]", "Qwen/Qwen3-Embedding-8B", 2, 5);
    assertEquals(1, hits.size());
    assertEquals(original, hits.getFirst().getContent());
    assertEquals(1.0, hits.getFirst().getSimilarity(), 1e-6);
    assertTrue(
        retrievalMapper.activeModelBindings().stream()
            .anyMatch(
                binding ->
                    binding.model().equals("Qwen/Qwen3-Embedding-8B")
                        && binding.dimensions() == 2));
    assertTrue(
        retrievalMapper.searchAll("[1,0]", "Qwen/Qwen3-Embedding-8B", 2, 50).stream()
            .anyMatch(hit -> hit.getDocumentId().equals(importedDocumentId)));
    var storage = config.getStorage();
    try (S3Client s3 =
        S3Client.builder()
            .endpointOverride(URI.create(storage.getEndpoint()))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(storage.getAccessKey(), storage.getSecretKey())))
            .build()) {
      byte[] stored =
          s3.getObjectAsBytes(b -> b.bucket(storage.getBucket()).key(version.getStorageKey()))
              .asByteArray();
      assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), stored);
    }
  }

  @Test
  void ranksByCosineAndNeverReturnsOtherKnowledgeBases() {
    UUID first = kb(), other = kb();
    var holiday = documents.upload(first, file("年假.md", "# 年假\n五天。"));
    documents.createChunks(first, holiday.documentId());
    var expense = documents.upload(first, file("报销.md", "# 报销\n三天内提交。"));
    documents.createChunks(first, expense.documentId());
    var secret = documents.upload(other, file("其他库.md", "# 机密\n不可跨库检索。"));
    documents.createChunks(other, secret.documentId());
    var hits = retrievalMapper.search(first, "[1,0]", "Qwen/Qwen3-Embedding-8B", 2, 10);
    assertEquals(2, hits.size());
    assertEquals(holiday.documentId(), hits.getFirst().getDocumentId());
    assertEquals(1.0, hits.getFirst().getSimilarity(), 1e-6);
    assertEquals(0.0, hits.get(1).getSimilarity(), 1e-6);
    assertTrue(hits.stream().noneMatch(h -> h.getDocumentName().equals("其他库.md")));
    var scopedHits =
        retrievalMapper.searchIn(List.of(first), "[1,0]", "Qwen/Qwen3-Embedding-8B", 2, 10);
    assertEquals(2, scopedHits.size());
    assertTrue(scopedHits.stream().noneMatch(h -> h.getDocumentName().equals("其他库.md")));
    assertEquals(
        List.of(new EmbeddingBinding("Qwen/Qwen3-Embedding-8B", 2)),
        retrievalMapper.activeModelBindingsIn(List.of(first)));
  }

  @Test
  void failedEmbeddingLeavesNoActiveVersionOrPartialChunks() {
    UUID kb = kb();
    when(embedding.embed(anyString(), anyInt(), anyList()))
        .thenThrow(ApiException.upstream("MODEL_TIMEOUT", "test"));
    var uploaded = documents.upload(kb, file("失败.md", "# 测试\n不应可检索。"));
    assertThrows(ApiException.class, () -> documents.createChunks(kb, uploaded.documentId()));
    var docs = documents.list(kb, 1, 10, null).items();
    assertEquals(1, docs.size());
    assertEquals("FAILED", docs.getFirst().status());
    assertNull(documentMapper.selectById(docs.getFirst().id()).getActiveVersionId());
    assertEquals(
        0,
        chunkMapper.selectCount(
            new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, docs.getFirst().id())));
    assertTrue(retrievalMapper.search(kb, "[1,0]", "test-embedding", 2, 10).isEmpty());
  }

  @Test
  void uploadDoesNotInvokeEmbeddingBeforeManualChunking() {
    UUID kb = kb();
    var uploaded = documents.upload(kb, file("待处理.md", "# 资料\n原内容。"));
    assertEquals("UPLOADED", documents.list(kb, 1, 10, null).items().getFirst().status());
    verify(embedding, never()).embed(anyString(), anyInt(), anyList());
    documents.createChunks(kb, uploaded.documentId());
    verify(embedding).embed(eq("Qwen/Qwen3-Embedding-8B"), eq(2), anyList());
  }

  @Test
  void databaseFailureAfterFirstChunkRollsBackWholeVersion() {
    UUID kb = kb();
    // The deliberately malformed second vector bypasses the mocked HTTP adapter,
    // forcing the real database constraint to fail after the first INSERT.
    when(embedding.embed(anyString(), anyInt(), anyList()))
        .thenReturn(List.of(new float[] {1, 0}, new float[] {1, 0, 0}));
    String multiChunk = "# 第一节\n" + "第一块内容。".repeat(300) + "\n\n# 第二节\n" + "第二块内容。".repeat(300);
    var uploaded = documents.upload(kb, file("回滚.md", multiChunk));
    assertThrows(ApiException.class, () -> documents.createChunks(kb, uploaded.documentId()));
    var doc = documents.list(kb, 1, 10, null).items().getFirst();
    assertEquals("FAILED", doc.status());
    assertNull(documentMapper.selectById(doc.id()).getActiveVersionId());
    assertEquals(
        0,
        chunkMapper.selectCount(
            new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocumentId, doc.id())));
  }

  @Test
  void rechunkingReadyDocumentReplacesExistingChunksAndVectors() {
    UUID kb = kb();
    var uploaded = documents.upload(kb, file("重分块.md", "# 资料\n需要重新生成索引。"));
    documents.createChunks(kb, uploaded.documentId());
    var original =
        chunkMapper
            .selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, uploaded.documentId()))
            .getFirst();

    var rebuilt = documents.createChunks(kb, uploaded.documentId());

    var replacement =
        chunkMapper
            .selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, uploaded.documentId()))
            .getFirst();
    assertEquals("READY", rebuilt.status());
    assertEquals(1, rebuilt.chunkCount());
    assertNotEquals(original.getId(), replacement.getId());
    assertEquals(
        1,
        chunkMapper.selectCount(
            new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, uploaded.documentId())));
  }

  @Test
  void invalidUtf8AndEmptyMarkdownDoNotCreateDocuments() {
    UUID kb = kb();
    assertThrows(
        ApiException.class,
        () ->
            documents.upload(
                kb,
                new MockMultipartFile(
                    "file", "bad.md", "text/markdown", new byte[] {(byte) 0xc3, 0x28})));
    assertThrows(ApiException.class, () -> documents.upload(kb, file("空.md", " \n")));
    assertTrue(documents.list(kb, 1, 10, null).items().isEmpty());
  }
}
