package com.hnu.backend.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.json.JsonCodecs;
import com.hnu.backend.conversation.entity.*;
import com.hnu.backend.conversation.mapper.*;
import com.hnu.backend.conversation.service.ConversationClarificationService;
import com.hnu.backend.conversation.service.ConversationService;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.model.client.EmbeddingClient;
import com.hnu.backend.rag.api.ClarificationContext;
import com.hnu.backend.rag.pipeline.QueryPlan;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 PostgreSQL 验证澄清约束、跨用户隔离和并发认领；只使用独立测试库。 */
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
class ClarificationIntegrationTest {
  @Autowired ConversationClarificationService service;
  @Autowired ConversationService conversationService;
  @Autowired ConversationMapper conversations;
  @Autowired MessageMapper messages;
  @Autowired ConversationClarificationMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;
  @MockitoBean com.hnu.backend.rag.api.RagEngine engine;
  @MockitoBean ChatClient chat;
  @MockitoBean EmbeddingClient embedding;
  private UUID owner;
  private UUID conversation;
  private UUID node;
  private Message original;
  private Message prompt;

  @BeforeEach
  void prepare() {
    owner =
        jdbc.queryForObject(
            "SELECT id FROM users WHERE username = 'integration-admin'", UUID.class);
    conversation = UUID.randomUUID();
    node = UUID.randomUUID();
    conversations.insert(owner, conversation, "KB 消歧测试");
    original = user(1, "怎么申请权限？");
    prompt = assistant(original);
    var state =
        new ClarificationContext(
            null,
            QueryPlan.fallback(original.getContent()),
            Map.of(),
            List.of(
                new ClarificationContext.Ambiguity(
                    "Q1",
                    List.of(
                        new ClarificationContext.Option(node, "财务 > 权限申请"),
                        new ClarificationContext.Option(UUID.randomUUID(), "OA > 权限申请")))),
            List.of());
    tx.executeWithoutResult(ignored -> service.complete(prompt.getId(), state));
  }

  @AfterEach
  void cleanup() {
    conversations.delete(owner, conversation);
  }

  @Test
  void selectionSurvivesRefreshFailureRetryAndResolution() {
    var pending = service.pending(conversation);
    assertEquals(
        pending.id(), conversationService.get(owner, conversation).pendingClarification().id());
    assertEquals(
        pending.id(),
        conversationService
            .page(owner, conversation, null, null, null, 1)
            .conversation()
            .pendingClarification()
            .id());
    Message supplement = user(2, "财务 > 权限申请");
    Message answer = assistant(supplement);
    tx.executeWithoutResult(ignored -> service.claim(supplement, answer, pending.id(), node));
    assertEquals("RESUMING", service.pending(conversation).status());
    assertThrows(ApiException.class, () -> service.cancel(owner, conversation, pending.id()));
    var request = service.request(owner, messages.selectById(supplement.getId()), false);
    assertTrue(request.question().startsWith(original.getContent()));
    assertTrue(request.question().contains("财务 > 权限申请"));
    assertEquals(node, request.clarification().selections().get("Q1"));
    tx.executeWithoutResult(ignored -> service.release(answer.getId()));
    assertEquals("PENDING", service.pending(conversation).status());
    service.checkRestart(answer, false);
    answer.setId(UUID.randomUUID());
    answer.setVariantIndex(2);
    answer.setActive(false);
    messages.insert(answer);
    tx.executeWithoutResult(ignored -> service.restart(supplement, answer));
    tx.executeWithoutResult(ignored -> service.complete(answer.getId(), null));
    assertNull(service.pending(conversation));
    assertTrue(messages.selectById(prompt.getId()).getClarificationJson().contains("RESOLVED"));
  }

  @Test
  void freeTextCanRotateClarificationAndCancelThenAskNewQuestion() {
    var pending = service.pending(conversation);
    Message supplement = user(2, "不确定");
    Message answer = assistant(supplement);
    tx.executeWithoutResult(ignored -> service.claim(supplement, answer, null, null));
    var context =
        JsonCodecs.snapshots()
            .readValue(supplement.getClarificationContextJson(), ClarificationContext.class);
    tx.executeWithoutResult(ignored -> service.complete(answer.getId(), context));
    var next = service.pending(conversation);
    assertNotEquals(pending.id(), next.id());
    assertThrows(ApiException.class, () -> service.question(conversation, pending.id(), node, ""));
    service.cancel(owner, conversation, next.id());
    service.cancel(owner, conversation, next.id());
    assertNull(service.pending(conversation));
    Message fresh = user(3, "新问题");
    Message freshAnswer = assistant(fresh);
    tx.executeWithoutResult(ignored -> service.claim(fresh, freshAnswer, null, null));
    assertNull(fresh.getClarificationContextJson());
  }

  @Test
  void foreignUserAndForgedNodeCannotActAndClarificationCannotRegenerate() {
    var pending = service.pending(conversation);
    assertThrows(
        ApiException.class, () -> service.cancel(UUID.randomUUID(), conversation, pending.id()));
    assertThrows(
        ApiException.class,
        () -> service.question(conversation, pending.id(), UUID.randomUUID(), ""));
    assertThrows(
        ApiException.class, () -> service.checkRestart(messages.selectById(prompt.getId()), true));
    assertEquals("PENDING", service.pending(conversation).status());
  }

  @Test
  void concurrentClaimsHaveExactlyOneWinner() throws Exception {
    var pending = service.pending(conversation);
    Message first = user(2, "财务");
    Message second = user(3, "财务");
    Message firstAnswer = assistant(first);
    Message secondAnswer = assistant(second);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (var pair : List.of(List.of(first, firstAnswer), List.of(second, secondAnswer))) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    tx.executeWithoutResult(
                        ignored -> service.claim(pair.get(0), pair.get(1), pending.id(), node));
                    return true;
                  } catch (ApiException error) {
                    return false;
                  }
                }));
      }
      start.countDown();
      int winners = 0;
      for (var result : results) {
        if (result.get(10, TimeUnit.SECONDS)) {
          winners++;
        }
      }
      assertEquals(1, winners);
    }
    assertEquals("RESUMING", service.pending(conversation).status());
  }

  @Test
  void interruptedGenerationReleasesPendingState() {
    var pending = service.pending(conversation);
    Message supplement = user(2, "财务");
    Message answer = assistant(supplement);
    tx.executeWithoutResult(ignored -> service.claim(supplement, answer, pending.id(), node));
    answer.setStatus(MessageStatus.CANCELLED);
    messages.updateById(answer);
    service.recover();
    assertEquals("PENDING", service.pending(conversation).status());
  }

  @Test
  void confirmationPreservesNotesAndProgressAcrossPendingRecords() {
    service.cancel(owner, conversation, service.pending(conversation).id());
    var options =
        List.of(
            new ClarificationContext.Option(node, "财务 > 权限申请", "报销和财务审批"),
            new ClarificationContext.Option(UUID.randomUUID(), "OA > 权限申请", "办公协作"));
    var plan =
        new QueryPlan(
            "申请权限并查询进度",
            List.of(
                new com.hnu.backend.rag.pipeline.SubQuestion("Q1", "怎么申请权限？"),
                new com.hnu.backend.rag.pipeline.SubQuestion("Q2", "在哪里查询进度？")));
    var context =
        new ClarificationContext(
            original.getId(),
            plan,
            Map.of(),
            List.of(
                new ClarificationContext.Ambiguity("Q1", options),
                new ClarificationContext.Ambiguity("Q2", options)),
            List.of());
    tx.executeWithoutResult(ignored -> service.complete(prompt.getId(), context));
    var pending = service.pending(conversation);
    assertEquals(1, pending.currentStep());
    assertEquals(2, pending.totalSteps());
    assertEquals("报销和财务审批", pending.options().getFirst().description());
    String merged = service.question(conversation, pending.id(), node, "只需要查看权限");
    assertEquals("财务 > 权限申请\n补充说明：只需要查看权限", merged);
    assertEquals(merged, service.question(conversation, pending.id(), node, merged));
    Message supplement = user(2, merged);
    Message answer = assistant(supplement);
    tx.executeWithoutResult(ignored -> service.claim(supplement, answer, pending.id(), node));
    var request = service.request(owner, supplement, false);
    assertTrue(request.question().contains("只需要查看权限"));
    var saved = request.clarification();
    assertEquals(2, saved.totalSteps());
    var next =
        new ClarificationContext(
            saved.originalMessageId(),
            saved.plan(),
            saved.selections(),
            saved.pending().subList(1, 2),
            saved.supplementIds(),
            saved.totalSteps());
    tx.executeWithoutResult(ignored -> service.complete(answer.getId(), next));
    var refreshed = conversationService.get(owner, conversation).pendingClarification();
    assertEquals(2, refreshed.currentStep());
    assertEquals(2, refreshed.totalSteps());
    assertEquals("在哪里查询进度？", refreshed.question());
  }

  @Test
  void duplicateSubmissionReplaysPersistedAnswerWithoutExecutingAgain() throws Exception {
    var pending = service.pending(conversation);
    var control = mock(com.hnu.backend.rag.api.RagExecutionControl.class);
    when(engine.newControl()).thenReturn(control);
    when(engine.execute(any(), any(), any(), any()))
        .thenReturn(
            new com.hnu.backend.rag.api.RagResult("财务系统权限申请说明", List.of(), List.of(), null));
    UUID requestId = UUID.randomUUID();
    var timing =
        new com.hnu.backend.common.web.RequestTiming(
            "integration", java.time.OffsetDateTime.now(), System.nanoTime());
    conversationService.ask(owner, conversation, requestId, null, timing, pending.id(), node);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    Message reply = null;
    while (System.nanoTime() < deadline) {
      Message user = messages.findByClientRequest(owner, conversation, requestId);
      reply = user == null ? null : messages.latestReply(owner, user.getId());
      if (reply != null && reply.getStatus() == MessageStatus.COMPLETED) {
        break;
      }
      Thread.sleep(10);
    }
    assertNotNull(reply);
    assertEquals(MessageStatus.COMPLETED, reply.getStatus());
    assertNull(service.pending(conversation));
    conversationService.ask(owner, conversation, requestId, null, timing, pending.id(), node);
    var input = org.mockito.ArgumentCaptor.forClass(com.hnu.backend.rag.api.RagRequest.class);
    verify(engine, times(1)).execute(input.capture(), any(), any(), any());
    assertTrue(input.getValue().question().startsWith(original.getContent()));
    assertEquals(node, input.getValue().clarification().selections().get("Q1"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM generation_attempts WHERE assistant_message_id = ?",
            Integer.class,
            reply.getId()));
  }

  private Message user(int turn, String content) {
    Message value = new Message();
    value.setId(UUID.randomUUID());
    value.setConversationId(conversation);
    value.setClientRequestId(UUID.randomUUID());
    value.setRole(MessageRole.USER);
    value.setTurnIndex(turn);
    value.setVariantIndex(0);
    value.setActive(true);
    value.setStatus(MessageStatus.COMPLETED);
    value.setContent(content);
    value.setSourcesJson("[]");
    value.setCitationsJson("[]");
    messages.insert(value);
    return value;
  }

  private Message assistant(Message user) {
    Message value = new Message();
    value.setId(UUID.randomUUID());
    value.setConversationId(conversation);
    value.setRole(MessageRole.ASSISTANT);
    value.setReplyToId(user.getId());
    value.setTurnIndex(user.getTurnIndex());
    value.setVariantIndex(1);
    value.setActive(true);
    value.setStatus(MessageStatus.COMPLETED);
    value.setContent("请选择意图");
    value.setSourcesJson("[]");
    value.setCitationsJson("[]");
    messages.insert(value);
    return value;
  }
}
