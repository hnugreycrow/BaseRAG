package com.hnu.backend.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.mapper.UserMapper;
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
import com.hnu.backend.observability.RagExecutionMode;
import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.RagStageStatus;
import com.hnu.backend.observability.entity.RagRun;
import com.hnu.backend.observability.entity.RagStageRun;
import com.hnu.backend.observability.mapper.RagRunMapper;
import com.hnu.backend.observability.mapper.RagStageRunMapper;
import com.hnu.backend.rag.retrieval.EmbeddingBinding;
import com.hnu.backend.rag.retrieval.RetrievalMapper;
import com.hnu.backend.shared.error.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
class InfrastructureIntegrationTest {
  @Autowired KnowledgeBaseMapper kbMapper;
  @Autowired UserMapper userMapper;
  @Autowired DocumentMapper documentMapper;
  @Autowired DocumentVersionMapper versionMapper;
  @Autowired DocumentChunkMapper chunkMapper;
  @Autowired RetrievalMapper retrievalMapper;
  @Autowired ConversationMapper conversationMapper;
  @Autowired MessageMapper messageMapper;
  @Autowired RagRunMapper ragRunMapper;
  @Autowired RagStageRunMapper ragStageRunMapper;
  @Autowired DocumentService documents;
  @Autowired com.hnu.backend.configuration.RagProperties config;
  @MockitoBean EmbeddingClient embedding;
  @MockitoBean ChatClient chat;

  @Test
  void persistsConversationMessagesAndJsonAuditSnapshots() {
    UUID conversationId = UUID.randomUUID();
    UUID ownerId = ownerId();
    conversationMapper.insert(ownerId, conversationId, "持久化测试");
    var conversation = conversationMapper.find(ownerId, conversationId);
    assertEquals("", conversation.getSummaryText());
    assertTrue(
        conversationMapper.list(ownerId, "持久化", 10).stream()
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
    messageMapper.prepare(ownerId, assistant.getId(), "独立问题", "[{\"citationId\":\"S1\"}]");
    messageMapper.complete(
        ownerId,
        assistant.getId(),
        "回答 [S1]",
        "[\"S1\"]",
        "{\"id\":\"test\",\"provider\":\"test\",\"model\":\"test\"}");

    var stored = messageMapper.find(ownerId, assistant.getId());
    assertEquals("COMPLETED", stored.getStatus());
    assertTrue(stored.getSourcesJson().contains("S1"));
    assertTrue(stored.getCitationsJson().contains("S1"));
    assertTrue(stored.getModelInfoJson().contains("test"));
    assertEquals(2, messageMapper.list(ownerId, conversationId).size());
    assertEquals(
        1,
        conversationMapper.updateSummary(
            ownerId, conversationId, "用户咨询了制度修订（当时已回答）", 1, conversation.getSummaryRevision()));
    var summarized = conversationMapper.find(ownerId, conversationId);
    assertEquals("用户咨询了制度修订（当时已回答）", summarized.getSummaryText());
    assertEquals(1, summarized.getSummarizedThroughTurn());
    assertEquals(1, summarized.getSummaryRevision());
    conversationMapper.delete(ownerId, conversationId);
    assertTrue(messageMapper.list(ownerId, conversationId).isEmpty());
  }

  @Test
  void cancellationIsIdempotentAndCannotBeOverwrittenByLateGenerationWork() {
    UUID conversationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID generationId = UUID.randomUUID();
    UUID ownerId = ownerId();
    conversationMapper.insert(ownerId, conversationId, "取消状态测试");

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

    assertEquals(1, messageMapper.cancelRunning(ownerId, generationId, conversationId, "部分回答"));
    assertEquals(0, messageMapper.cancelRunning(ownerId, generationId, conversationId, "被覆盖"));
    assertEquals(0, messageMapper.markStreaming(ownerId, generationId));
    assertEquals(
        0,
        messageMapper.complete(
            ownerId,
            generationId,
            "迟到的完整回答",
            "[]",
            "{\"id\":\"late\",\"provider\":\"test\",\"model\":\"test\"}"));

    Message stored = messageMapper.find(ownerId, generationId);
    assertEquals("CANCELLED", stored.getStatus());
    assertEquals("部分回答", stored.getContent());
    assertEquals("GENERATION_CANCELLED", stored.getErrorCode());
    conversationMapper.delete(ownerId, conversationId);
  }

  @Test
  void retainsTraceAfterConversationDeletionAndCascadesItAfterRetentionCleanup() {
    UUID ownerId = ownerId();
    UUID conversationId = UUID.randomUUID();
    conversationMapper.insert(ownerId, conversationId, "Trace 保留测试");

    Message user = new Message();
    user.setId(UUID.randomUUID());
    user.setConversationId(conversationId);
    user.setClientRequestId(UUID.randomUUID());
    user.setRole("USER");
    user.setTurnIndex(1);
    user.setVariantIndex(0);
    user.setActive(true);
    user.setStatus("COMPLETED");
    user.setContent("不会进入 Trace 的问题正文");
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
    assistant.setStatus("COMPLETED");
    assistant.setContent("不会进入 Trace 的回答正文");
    assistant.setSourcesJson("[]");
    assistant.setCitationsJson("[]");
    messageMapper.insert(assistant);

    OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusDays(31);
    RagRun run = new RagRun();
    run.setId(UUID.randomUUID());
    run.setOwnerId(ownerId);
    run.setRequestId(UUID.randomUUID().toString());
    run.setConversationId(conversationId);
    run.setUserMessageId(user.getId());
    run.setAssistantMessageId(assistant.getId());
    run.setStatus(RagRunStatus.COMPLETED);
    run.setExecutionMode(RagExecutionMode.FULL_PIPELINE);
    run.setStartedAt(old);
    run.setCompletedAt(old.plusSeconds(1));
    run.setTotalMs(1000L);
    ragRunMapper.insert(run);

    RagStageRun stage = new RagStageRun();
    stage.setId(UUID.randomUUID());
    stage.setRagRunId(run.getId());
    stage.setStageName(RagStageName.MEMORY_LOAD);
    stage.setSequenceNo(1);
    stage.setStatus(RagStageStatus.SUCCESS);
    stage.setStartedAt(old);
    stage.setCompletedAt(old.plusNanos(1_000_000));
    stage.setElapsedMs(1);
    ragStageRunMapper.insertBatch(List.of(stage));

    conversationMapper.delete(ownerId, conversationId);

    RagRun retained = ragRunMapper.selectById(run.getId());
    assertNotNull(retained);
    assertNull(retained.getConversationId());
    assertNull(retained.getUserMessageId());
    assertNull(retained.getAssistantMessageId());
    assertEquals(1, ragStageRunMapper.listByRun(run.getId()).size());

    assertTrue(ragRunMapper.deleteExpired(30) >= 1);
    assertNull(ragRunMapper.selectById(run.getId()));
    assertTrue(ragStageRunMapper.listByRun(run.getId()).isEmpty());
  }

  @BeforeEach
  void testModels() {
    when(embedding.embed(anyString(), anyString(), anyString(), anyInt(), anyList()))
        .thenAnswer(
            invocation -> {
              List<String> inputs = invocation.getArgument(4);
              return inputs.stream()
                  .map(input -> input.contains("报销") ? new float[] {0, 1} : new float[] {1, 0})
                  .toList();
            });
    when(chat.generate(anyString(), anyString()))
        .thenReturn(new ChatClient.Generation("员工年假为五天。[S1]", "test-chat", "test", "test-chat"));
  }

  private UUID kb() {
    return kb(ownerId());
  }

  private UUID kb(UUID ownerId) {
    var kb = new KnowledgeBase();
    kb.setId(UUID.randomUUID());
    kb.setOwnerId(ownerId);
    kb.setName("自动化测试");
    kb.setEmbeddingModelId("qwen-emb-8b");
    kb.setEmbeddingProvider("siliconflow");
    kb.setEmbeddingModel("Qwen/Qwen3-Embedding-8B");
    kb.setEmbeddingDimensions(2);
    kbMapper.insert(kb);
    return kb.getId();
  }

  private UUID createUser() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("integration-" + user.getId());
    user.setDisplayName("隔离测试用户");
    user.setPasswordHash("$2a$12$not-used-by-this-integration-test");
    user.setRole(com.hnu.backend.auth.entity.UserRole.USER);
    user.setEnabled(true);
    userMapper.insert(user);
    return user.getId();
  }

  private UUID ownerId() {
    return userMapper
        .selectList(
            Wrappers.<User>lambdaQuery()
                .ne(User::getId, UserMapper.LEGACY_OWNER_ID)
                .eq(User::isEnabled, true)
                .orderByAsc(User::getCreatedAt)
                .last("LIMIT 1"))
        .getFirst()
        .getId();
  }

  private MockMultipartFile file(String name, String text) {
    return new MockMultipartFile(
        "file", name, "text/markdown", text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void persistsSectionEmbeddingTextWithoutChangingDisplayedSource() {
    UUID ownerId = ownerId();
    UUID knowledgeBaseId = kb();
    var uploaded =
        documents.upload(
            ownerId, knowledgeBaseId, file("章节.md", "# 手册\n简介。\n\n## 年假\n年假五天。\n\n## 报销\n三天内报销。"));
    var ready = documents.createChunks(ownerId, knowledgeBaseId, uploaded.documentId());
    assertEquals(1, ready.chunkCount());
    var policy =
        chunkMapper
            .selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, uploaded.documentId())
                    .orderByAsc(DocumentChunk::getChunkIndex))
            .getFirst();
    assertEquals("手册", policy.getHeading());
    assertTrue(policy.getContent().contains("## 年假"));
    assertTrue(policy.getContent().contains("年假五天。"));
    assertTrue(policy.getContent().contains("## 报销"));
    assertTrue(policy.getEmbeddingText().contains("年假"));
    assertTrue(policy.getEmbeddingText().contains("报销"));
    assertEquals(1, policy.getLineStart());
    assertEquals(8, policy.getLineEnd());
  }

  @Test
  void importsRealStorageAndVectorsAnswersFromActualDatabaseText() {
    UUID kb = kb();
    UUID ownerId = ownerId();
    String original = "# 年假\n员工年假为五天。";
    var imported = documents.upload(ownerId, kb, file("手册.md", original));
    assertEquals("UPLOADED", imported.status());
    assertEquals(0, imported.chunkCount());
    imported = documents.createChunks(ownerId, kb, imported.documentId());
    assertEquals("READY", imported.status());
    UUID importedDocumentId = imported.documentId();
    var document = documentMapper.selectById(importedDocumentId);
    assertNotNull(document.getActiveVersionId());
    var version = versionMapper.selectById(document.getActiveVersionId());
    assertTrue(
        version.getStorageKey().startsWith("users/" + ownerId + "/knowledge-bases/" + kb + "/"));
    var hits =
        retrievalMapper.search(
            ownerId, kb, "[1,0]", "qwen-emb-8b", "siliconflow", "Qwen/Qwen3-Embedding-8B", 2, 5);
    assertEquals(1, hits.size());
    assertEquals(original, hits.getFirst().getContent().replaceAll("\\n{2,}", "\n"));
    assertEquals(1.0, hits.getFirst().getSimilarity(), 1e-6);
    assertTrue(
        retrievalMapper.activeModelBindings(ownerId).stream()
            .anyMatch(
                binding ->
                    binding.modelId().equals("qwen-emb-8b")
                        && binding.provider().equals("siliconflow")
                        && binding.model().equals("Qwen/Qwen3-Embedding-8B")
                        && binding.dimensions() == 2));
    assertTrue(
        retrievalMapper
            .searchAll(
                ownerId, "[1,0]", "qwen-emb-8b", "siliconflow", "Qwen/Qwen3-Embedding-8B", 2, 5000)
            .stream()
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
    UUID ownerId = ownerId();
    UUID first = kb(), other = kb();
    var holiday = documents.upload(ownerId, first, file("年假.md", "# 年假\n五天。"));
    documents.createChunks(ownerId, first, holiday.documentId());
    var expense = documents.upload(ownerId, first, file("报销.md", "# 报销\n三天内提交。"));
    documents.createChunks(ownerId, first, expense.documentId());
    var secret = documents.upload(ownerId, other, file("其他库.md", "# 机密\n不可跨库检索。"));
    documents.createChunks(ownerId, other, secret.documentId());
    var hits =
        retrievalMapper.search(
            ownerId,
            first,
            "[1,0]",
            "qwen-emb-8b",
            "siliconflow",
            "Qwen/Qwen3-Embedding-8B",
            2,
            10);
    assertEquals(2, hits.size());
    assertEquals(holiday.documentId(), hits.getFirst().getDocumentId());
    assertEquals(1.0, hits.getFirst().getSimilarity(), 1e-6);
    assertEquals(0.0, hits.get(1).getSimilarity(), 1e-6);
    assertTrue(hits.stream().noneMatch(h -> h.getDocumentName().equals("其他库.md")));
    var scopedHits =
        retrievalMapper.searchIn(
            ownerId,
            List.of(first),
            "[1,0]",
            "qwen-emb-8b",
            "siliconflow",
            "Qwen/Qwen3-Embedding-8B",
            2,
            10);
    assertEquals(2, scopedHits.size());
    assertTrue(scopedHits.stream().noneMatch(h -> h.getDocumentName().equals("其他库.md")));
    assertEquals(
        List.of(new EmbeddingBinding("qwen-emb-8b", "siliconflow", "Qwen/Qwen3-Embedding-8B", 2)),
        retrievalMapper.activeModelBindingsIn(ownerId, List.of(first)));
  }

  @Test
  void neverReturnsKnowledgeOrConversationDataOwnedByAnotherUser() {
    UUID ownerId = ownerId();
    UUID otherOwnerId = createUser();
    UUID otherKnowledgeBaseId = kb(otherOwnerId);
    var secret = documents.upload(otherOwnerId, otherKnowledgeBaseId, file("私有.md", "# 私有\n不可泄露。"));
    documents.createChunks(otherOwnerId, otherKnowledgeBaseId, secret.documentId());

    ApiException hidden =
        assertThrows(
            ApiException.class, () -> documents.list(ownerId, otherKnowledgeBaseId, 1, 10, null));
    assertEquals("KNOWLEDGE_BASE_NOT_FOUND", hidden.code());
    assertTrue(
        retrievalMapper
            .searchIn(
                ownerId,
                List.of(otherKnowledgeBaseId),
                "[1,0]",
                "qwen-emb-8b",
                "siliconflow",
                "Qwen/Qwen3-Embedding-8B",
                2,
                10)
            .isEmpty());

    UUID otherConversationId = UUID.randomUUID();
    conversationMapper.insert(otherOwnerId, otherConversationId, "私有会话");
    assertNull(conversationMapper.find(ownerId, otherConversationId));
    assertNotNull(conversationMapper.find(otherOwnerId, otherConversationId));
  }

  @Test
  void failedEmbeddingLeavesNoActiveVersionOrPartialChunks() {
    UUID kb = kb();
    UUID ownerId = ownerId();
    when(embedding.embed(anyString(), anyString(), anyString(), anyInt(), anyList()))
        .thenThrow(ApiException.upstream("MODEL_TIMEOUT", "test"));
    var uploaded = documents.upload(ownerId, kb, file("失败.md", "# 测试\n不应可检索。"));
    assertThrows(
        ApiException.class, () -> documents.createChunks(ownerId, kb, uploaded.documentId()));
    var docs = documents.list(ownerId, kb, 1, 10, null).items();
    assertEquals(1, docs.size());
    assertEquals("FAILED", docs.getFirst().status());
    assertNull(documentMapper.selectById(docs.getFirst().id()).getActiveVersionId());
    assertEquals(
        0,
        chunkMapper.selectCount(
            new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, docs.getFirst().id())));
    assertTrue(
        retrievalMapper
            .search(ownerId, kb, "[1,0]", "qwen-emb-8b", "siliconflow", "test-embedding", 2, 10)
            .isEmpty());
  }

  @Test
  void uploadDoesNotInvokeEmbeddingBeforeManualChunking() {
    UUID kb = kb();
    UUID ownerId = ownerId();
    var uploaded = documents.upload(ownerId, kb, file("待处理.md", "# 资料\n原内容。"));
    assertEquals("UPLOADED", documents.list(ownerId, kb, 1, 10, null).items().getFirst().status());
    verify(embedding, never()).embed(anyString(), anyString(), anyString(), anyInt(), anyList());
    documents.createChunks(ownerId, kb, uploaded.documentId());
    verify(embedding)
        .embed(
            eq("qwen-emb-8b"), eq("siliconflow"), eq("Qwen/Qwen3-Embedding-8B"), eq(2), anyList());
  }

  @Test
  void databaseFailureAfterFirstChunkRollsBackWholeVersion() {
    UUID kb = kb();
    UUID ownerId = ownerId();
    // 第二个向量故意使用错误维度，绕过模拟的 HTTP 适配器，
    // 让真实数据库约束在第一条分块写入后触发失败，以验证整版回滚。
    when(embedding.embed(anyString(), anyString(), anyString(), anyInt(), anyList()))
        .thenReturn(List.of(new float[] {1, 0}, new float[] {1, 0, 0}));
    String multiChunk = "# 第一节\n" + "第一块内容。".repeat(300) + "\n\n# 第二节\n" + "第二块内容。".repeat(300);
    var uploaded = documents.upload(ownerId, kb, file("回滚.md", multiChunk));
    assertThrows(
        ApiException.class, () -> documents.createChunks(ownerId, kb, uploaded.documentId()));
    var doc = documents.list(ownerId, kb, 1, 10, null).items().getFirst();
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
    UUID ownerId = ownerId();
    var uploaded = documents.upload(ownerId, kb, file("重分块.md", "# 资料\n需要重新生成索引。"));
    documents.createChunks(ownerId, kb, uploaded.documentId());
    var original =
        chunkMapper
            .selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, uploaded.documentId()))
            .getFirst();

    var rebuilt = documents.createChunks(ownerId, kb, uploaded.documentId());

    var replacement =
        chunkMapper
            .selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, uploaded.documentId()))
            .getFirst();
    assertEquals("READY", rebuilt.status());
    assertEquals(1, rebuilt.chunkCount());
    assertEquals(
        "structured-block-v6",
        versionMapper
            .selectById(documentMapper.selectById(uploaded.documentId()).getActiveVersionId())
            .getChunkerVersion());
    assertNotEquals(original.getId(), replacement.getId());
    assertEquals(
        1,
        chunkMapper.selectCount(
            new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, uploaded.documentId())));
  }

  @Test
  void asyncChunkingReturnsBeforeEmbeddingAndRejectsDuplicateSubmission() throws Exception {
    UUID ownerId = ownerId();
    UUID knowledgeBaseId = kb();
    var uploaded = documents.upload(ownerId, knowledgeBaseId, file("异步.md", "# 资料\n等待向量化。"));
    var started = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    when(embedding.embed(anyString(), anyString(), anyString(), anyInt(), anyList()))
        .thenAnswer(
            invocation -> {
              started.countDown();
              if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("embedding test timed out");
              List<String> inputs = invocation.getArgument(4);
              return inputs.stream().map(ignored -> new float[] {1, 0}).toList();
            });
    try {
      var accepted = documents.enqueueChunks(ownerId, knowledgeBaseId, uploaded.documentId());
      assertEquals("PROCESSING", accepted.status());
      assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(
          "PROCESSING", documents.get(ownerId, knowledgeBaseId, uploaded.documentId()).status());
      ApiException duplicate =
          assertThrows(
              ApiException.class,
              () -> documents.enqueueChunks(ownerId, knowledgeBaseId, uploaded.documentId()));
      assertEquals("DOCUMENT_PROCESSING", duplicate.code());
    } finally {
      release.countDown();
    }
    awaitDocumentStatus(ownerId, knowledgeBaseId, uploaded.documentId(), "READY");
  }

  @Test
  void boundedQueueRunsAtMostTwoTasksAndRejectsOverflow() throws Exception {
    UUID ownerId = ownerId();
    UUID knowledgeBaseId = kb();
    List<UUID> documentIds = new ArrayList<>();
    for (int i = 0; i < 53; i++) {
      documentIds.add(
          documents
              .upload(ownerId, knowledgeBaseId, file("queue-" + i + ".md", "# 资料\n任务 " + i))
              .documentId());
    }
    var started = new java.util.concurrent.CountDownLatch(2);
    var release = new java.util.concurrent.CountDownLatch(1);
    var active = new java.util.concurrent.atomic.AtomicInteger();
    var maximum = new java.util.concurrent.atomic.AtomicInteger();
    when(embedding.embed(anyString(), anyString(), anyString(), anyInt(), anyList()))
        .thenAnswer(
            invocation -> {
              int count = active.incrementAndGet();
              maximum.accumulateAndGet(count, Math::max);
              started.countDown();
              try {
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                  throw new IllegalStateException("embedding test timed out");
                List<String> inputs = invocation.getArgument(4);
                return inputs.stream().map(ignored -> new float[] {1, 0}).toList();
              } finally {
                active.decrementAndGet();
              }
            });
    try {
      documents.enqueueChunks(ownerId, knowledgeBaseId, documentIds.get(0));
      documents.enqueueChunks(ownerId, knowledgeBaseId, documentIds.get(1));
      assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
      List<UUID> mixedSelection = new ArrayList<>();
      mixedSelection.add(documentIds.get(0));
      mixedSelection.addAll(documentIds.subList(2, 51));
      var batch = documents.enqueueBatch(ownerId, knowledgeBaseId, mixedSelection, true);
      assertEquals(49, batch.acceptedDocumentIds().size());
      assertEquals(List.of(documentIds.get(0)), batch.skippedDocumentIds());
      documents.enqueueChunks(ownerId, knowledgeBaseId, documentIds.get(51));
      var onlyProcessing =
          documents.enqueueBatch(ownerId, knowledgeBaseId, List.of(documentIds.get(1)), true);
      assertTrue(onlyProcessing.acceptedDocumentIds().isEmpty());
      assertEquals(List.of(documentIds.get(1)), onlyProcessing.skippedDocumentIds());
      ApiException overflow =
          assertThrows(
              ApiException.class,
              () -> documents.enqueueChunks(ownerId, knowledgeBaseId, documentIds.get(52)));
      assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, overflow.status());
      assertEquals(
          "UPLOADED", documents.get(ownerId, knowledgeBaseId, documentIds.get(52)).status());
      assertEquals(2, maximum.get());
    } finally {
      release.countDown();
    }
    awaitDocumentStatus(ownerId, knowledgeBaseId, documentIds.get(51), "READY");
    assertEquals(2, maximum.get());
  }

  @Test
  void batchAcceptsPendingFailedAndReadyButSkipsProcessing() throws Exception {
    UUID ownerId = ownerId();
    UUID knowledgeBaseId = kb();
    var pending = documents.upload(ownerId, knowledgeBaseId, file("待办.md", "# 待办\n尚未处理。"));
    var failed = documents.upload(ownerId, knowledgeBaseId, file("失败重试.md", "# 重试\n需要处理。"));
    var ready = documents.upload(ownerId, knowledgeBaseId, file("就绪.md", "# 就绪\n已有索引。"));
    var processing = documents.upload(ownerId, knowledgeBaseId, file("处理中.md", "# 处理中\n当前任务。"));
    documents.createChunks(ownerId, knowledgeBaseId, ready.documentId());
    UUID oldChunk =
        chunkMapper
            .selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, ready.documentId()))
            .getFirst()
            .getId();
    versionMapper.update(
        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                com.hnu.backend.document.entity.DocumentVersion>()
            .eq(com.hnu.backend.document.entity.DocumentVersion::getDocumentId, failed.documentId())
            .set(com.hnu.backend.document.entity.DocumentVersion::getStatus, "FAILED"));
    versionMapper.update(
        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                com.hnu.backend.document.entity.DocumentVersion>()
            .eq(
                com.hnu.backend.document.entity.DocumentVersion::getDocumentId,
                processing.documentId())
            .set(com.hnu.backend.document.entity.DocumentVersion::getStatus, "PROCESSING"));

    var result =
        documents.enqueueBatch(
            ownerId,
            knowledgeBaseId,
            List.of(
                pending.documentId(),
                failed.documentId(),
                ready.documentId(),
                processing.documentId()),
            true);
    assertEquals(
        List.of(pending.documentId(), failed.documentId(), ready.documentId()),
        result.acceptedDocumentIds());
    assertEquals(List.of(processing.documentId()), result.skippedDocumentIds());
    awaitDocumentStatus(ownerId, knowledgeBaseId, pending.documentId(), "READY");
    awaitDocumentStatus(ownerId, knowledgeBaseId, failed.documentId(), "READY");
    awaitDocumentStatus(ownerId, knowledgeBaseId, ready.documentId(), "READY");
    assertNotEquals(
        oldChunk,
        chunkMapper
            .selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, ready.documentId()))
            .getFirst()
            .getId());
    assertEquals(
        "PROCESSING", documents.get(ownerId, knowledgeBaseId, processing.documentId()).status());
    documents.markInterruptedTasks();
  }

  @Test
  void batchChecksOwnershipBeforeClaimingAnyDocument() {
    UUID ownerId = ownerId();
    UUID knowledgeBaseId = kb();
    UUID otherKnowledgeBaseId = kb();
    var pending = documents.upload(ownerId, knowledgeBaseId, file("本库.md", "# 本库\n未处理。"));
    var foreign = documents.upload(ownerId, otherKnowledgeBaseId, file("其他库.md", "# 其他库\n未处理。"));
    ApiException rejected =
        assertThrows(
            ApiException.class,
            () ->
                documents.enqueueBatch(
                    ownerId,
                    knowledgeBaseId,
                    List.of(pending.documentId(), foreign.documentId()),
                    true));
    assertEquals("DOCUMENT_NOT_FOUND", rejected.code());
    assertEquals(
        "UPLOADED", documents.get(ownerId, knowledgeBaseId, pending.documentId()).status());
  }

  @Test
  void concurrentBatchSubmissionsClaimDocumentOnlyOnce() throws Exception {
    UUID ownerId = ownerId();
    UUID knowledgeBaseId = kb();
    var uploaded = documents.upload(ownerId, knowledgeBaseId, file("竞态.md", "# 竞态\n只处理一次。"));
    var release = new java.util.concurrent.CountDownLatch(1);
    when(embedding.embed(anyString(), anyString(), anyString(), anyInt(), anyList()))
        .thenAnswer(
            invocation -> {
              if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("embedding test timed out");
              List<String> inputs = invocation.getArgument(4);
              return inputs.stream().map(ignored -> new float[] {1, 0}).toList();
            });
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var first =
          executor.submit(
              () -> {
                start.await();
                return documents.enqueueBatch(
                    ownerId, knowledgeBaseId, List.of(uploaded.documentId()), true);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return documents.enqueueBatch(
                    ownerId, knowledgeBaseId, List.of(uploaded.documentId()), true);
              });
      try {
        start.countDown();
        var firstResult = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        var secondResult = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(
            1,
            firstResult.acceptedDocumentIds().size() + secondResult.acceptedDocumentIds().size());
        assertEquals(
            1, firstResult.skippedDocumentIds().size() + secondResult.skippedDocumentIds().size());
      } finally {
        release.countDown();
      }
    }
    awaitDocumentStatus(ownerId, knowledgeBaseId, uploaded.documentId(), "READY");
  }

  @Test
  void rechunkingKeepsOldEvidenceSearchableUntilAtomicSwap() throws Exception {
    UUID ownerId = ownerId();
    UUID knowledgeBaseId = kb();
    var uploaded = documents.upload(ownerId, knowledgeBaseId, file("重建异步.md", "# 资料\n旧索引可检索。"));
    documents.createChunks(ownerId, knowledgeBaseId, uploaded.documentId());
    UUID oldChunk =
        chunkMapper
            .selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, uploaded.documentId()))
            .getFirst()
            .getId();
    var started = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    when(embedding.embed(anyString(), anyString(), anyString(), anyInt(), anyList()))
        .thenAnswer(
            invocation -> {
              started.countDown();
              if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("embedding test timed out");
              List<String> inputs = invocation.getArgument(4);
              return inputs.stream().map(ignored -> new float[] {1, 0}).toList();
            });
    try {
      documents.enqueueChunks(ownerId, knowledgeBaseId, uploaded.documentId());
      assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(
          "PROCESSING", documents.get(ownerId, knowledgeBaseId, uploaded.documentId()).status());
      assertEquals(
          oldChunk,
          retrievalMapper
              .search(
                  ownerId,
                  knowledgeBaseId,
                  "[1,0]",
                  "qwen-emb-8b",
                  "siliconflow",
                  "Qwen/Qwen3-Embedding-8B",
                  2,
                  10)
              .getFirst()
              .getChunkId());
    } finally {
      release.countDown();
    }
    awaitDocumentStatus(ownerId, knowledgeBaseId, uploaded.documentId(), "READY");
    UUID replacement =
        chunkMapper
            .selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, uploaded.documentId()))
            .getFirst()
            .getId();
    assertNotEquals(oldChunk, replacement);
  }

  @Test
  void interruptedTasksBecomeRetryableWithoutAutomaticReplay() {
    UUID ownerId = ownerId();
    UUID knowledgeBaseId = kb();
    var fresh = documents.upload(ownerId, knowledgeBaseId, file("中断首次.md", "# 首次\n重试。"));
    var rebuilt = documents.upload(ownerId, knowledgeBaseId, file("中断重建.md", "# 重建\n旧索引。"));
    documents.createChunks(ownerId, knowledgeBaseId, rebuilt.documentId());
    versionMapper.update(
        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                com.hnu.backend.document.entity.DocumentVersion>()
            .eq(com.hnu.backend.document.entity.DocumentVersion::getDocumentId, fresh.documentId())
            .set(com.hnu.backend.document.entity.DocumentVersion::getStatus, "PROCESSING"));
    versionMapper.update(
        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                com.hnu.backend.document.entity.DocumentVersion>()
            .eq(
                com.hnu.backend.document.entity.DocumentVersion::getDocumentId,
                rebuilt.documentId())
            .set(com.hnu.backend.document.entity.DocumentVersion::getStatus, "PROCESSING"));

    documents.markInterruptedTasks();

    assertEquals("FAILED", documents.get(ownerId, knowledgeBaseId, fresh.documentId()).status());
    assertEquals("READY", documents.get(ownerId, knowledgeBaseId, rebuilt.documentId()).status());
    assertEquals(
        "IMPORT_INTERRUPTED",
        documents.get(ownerId, knowledgeBaseId, fresh.documentId()).errorCode());
    assertEquals(
        "IMPORT_INTERRUPTED",
        documents.get(ownerId, knowledgeBaseId, rebuilt.documentId()).errorCode());
    documents.createChunks(ownerId, knowledgeBaseId, fresh.documentId());
    assertEquals("READY", documents.get(ownerId, knowledgeBaseId, fresh.documentId()).status());
  }

  private void awaitDocumentStatus(
      UUID ownerId, UUID knowledgeBaseId, UUID documentId, String expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (expected.equals(documents.get(ownerId, knowledgeBaseId, documentId).status())) return;
      Thread.sleep(25);
    }
    fail("Document did not reach status " + expected);
  }

  @Test
  void invalidUtf8AndEmptyMarkdownDoNotCreateDocuments() {
    UUID kb = kb();
    UUID ownerId = ownerId();
    assertThrows(
        ApiException.class,
        () ->
            documents.upload(
                ownerId,
                kb,
                new MockMultipartFile(
                    "file", "bad.md", "text/markdown", new byte[] {(byte) 0xc3, 0x28})));
    assertThrows(ApiException.class, () -> documents.upload(ownerId, kb, file("空.md", " \n")));
    assertTrue(documents.list(ownerId, kb, 1, 10, null).items().isEmpty());
  }
}
