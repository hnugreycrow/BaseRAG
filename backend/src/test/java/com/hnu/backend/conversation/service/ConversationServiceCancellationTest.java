package com.hnu.backend.conversation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.hnu.backend.configuration.ConversationProperties;
import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.entity.GenerationAttempt;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.GenerationAttemptMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.rag.answer.AnswerStage;
import com.hnu.backend.rag.answer.ChatAnswerGenerator;
import com.hnu.backend.rag.answer.ContextBuilder;
import com.hnu.backend.rag.deduplication.DeduplicationResult;
import com.hnu.backend.rag.deduplication.DeduplicationStage;
import com.hnu.backend.rag.execution.CancellationToken;
import com.hnu.backend.rag.execution.ExecutionResult;
import com.hnu.backend.rag.execution.ExecutionStage;
import com.hnu.backend.rag.execution.RagBudgetSnapshot;
import com.hnu.backend.rag.execution.SubQuestionExecution;
import com.hnu.backend.rag.mcp.ToolObservation;
import com.hnu.backend.rag.planning.QueryPlan;
import com.hnu.backend.rag.planning.SubQuestion;
import com.hnu.backend.rag.prompt.PromptAssemblyStage;
import com.hnu.backend.rag.rerank.RerankResult;
import com.hnu.backend.rag.rerank.RerankStage;
import com.hnu.backend.rag.routing.IntentRoute;
import com.hnu.backend.rag.routing.IntentType;
import com.hnu.backend.rag.routing.RoutingPlan;
import com.hnu.backend.rag.routing.RoutingReasonCode;
import com.hnu.backend.shared.error.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class ConversationServiceCancellationTest {
  @Mock private ConversationMapper conversations;
  @Mock private MessageMapper messages;
  @Mock private GenerationAttemptMapper attempts;
  @Mock private ConversationContextService conversationContext;
  @Mock private ExecutionStage executionStage;
  @Mock private DeduplicationStage deduplicationStage;
  @Mock private RerankStage rerankStage;
  @Mock private ChatClient chat;
  @Mock private RagProperties rag;
  @Mock private ConversationProperties config;
  @Mock private TransactionTemplate tx;

  private ConversationService service;
  private final PromptAssemblyStage prompts =
      new PromptAssemblyStage(new ContextBuilder(new RagProperties()));

  @BeforeEach
  void setUp() {
    service =
        new ConversationService(
            conversations,
            messages,
            attempts,
            conversationContext,
            executionStage,
            deduplicationStage,
            rerankStage,
            prompts,
            new AnswerStage(new ChatAnswerGenerator(chat), prompts),
            rag,
            config,
            tx);
  }

  @AfterEach
  void tearDown() {
    service.close();
  }

  @Test
  void cancelsPersistedRunningGenerationWhenInMemoryTaskIsMissing() {
    UUID conversationId = UUID.randomUUID();
    UUID generationId = UUID.randomUUID();
    Message pending = assistant(conversationId, generationId, "PENDING");
    when(messages.find(generationId)).thenReturn(pending);
    when(messages.cancelRunning(generationId, conversationId, "partial")).thenReturn(1);
    runTransactionsImmediately();

    service.cancel(conversationId, generationId);

    InOrder order = inOrder(messages, attempts, conversations);
    order.verify(messages).cancelRunning(generationId, conversationId, "partial");
    order.verify(attempts).cancelRunning(generationId);
    order.verify(conversations).touch(conversationId);
  }

  @Test
  void repeatedCancellationOfTerminalGenerationIsIdempotent() {
    UUID conversationId = UUID.randomUUID();
    UUID generationId = UUID.randomUUID();
    when(messages.find(generationId))
        .thenReturn(assistant(conversationId, generationId, "CANCELLED"));

    service.cancel(conversationId, generationId);

    verify(tx, never()).executeWithoutResult(any());
  }

  @Test
  void rejectsGenerationFromAnotherConversation() {
    UUID conversationId = UUID.randomUUID();
    UUID generationId = UUID.randomUUID();
    when(messages.find(generationId))
        .thenReturn(assistant(UUID.randomUUID(), generationId, "PENDING"));

    ApiException error =
        assertThrows(ApiException.class, () -> service.cancel(conversationId, generationId));

    assertEquals("GENERATION_NOT_FOUND", error.code());
    verify(tx, never()).executeWithoutResult(any());
  }

  @Test
  void retrievesAndPersistsTheStandaloneQuestionFromTheQueryPlan() {
    UUID conversationId = UUID.randomUUID();
    Conversation conversation = new Conversation();
    conversation.setId(conversationId);
    when(conversations.find(conversationId)).thenReturn(conversation);
    when(messages.nextTurn(conversationId)).thenReturn(1);
    when(rag.getMaxQuestionChars()).thenReturn(2000);
    when(conversationContext.prepare(any(Conversation.class), eq(1), eq("原问题")))
        .thenReturn(preparedMixed("改写后的独立问题"));
    ExecutionResult empty =
        new ExecutionResult(List.of(), List.of(), RagBudgetSnapshot.from(new RagProperties()));
    when(executionStage.execute(any(), any(), isNull(), any(CancellationToken.class)))
        .thenReturn(empty);
    when(deduplicationStage.execute(empty.candidates(), empty.budget()))
        .thenReturn(new DeduplicationResult(List.of(), 0, 0, 0));
    when(rerankStage.execute(any(), eq(empty), eq(List.of()), any(CancellationToken.class)))
        .thenReturn(
            new RerankResult(
                List.of(),
                List.of(),
                RerankResult.Status.EMPTY,
                "NO_RERANK_INPUT",
                null,
                null,
                null,
                null,
                0));
    when(chat.stream(anyString(), anyString(), any(), any()))
        .thenReturn(new ChatClient.Generation("无法确认", "chat", "test", "model"));
    runTransactionsWithResultImmediately();
    runTransactionsImmediately();

    service.ask(conversationId, UUID.randomUUID(), "原问题", "request-id");

    verify(executionStage, timeout(2000))
        .execute(any(), any(), isNull(), any(CancellationToken.class));
    verify(deduplicationStage, timeout(2000)).execute(empty.candidates(), empty.budget());
    verify(rerankStage, timeout(2000))
        .execute(any(), eq(empty), eq(List.of()), any(CancellationToken.class));
    verify(messages, timeout(2000)).prepare(any(UUID.class), eq("改写后的独立问题"), eq("[]"));
  }

  @Test
  void systemChatSkipsRetrievalAndPersistsEmptySources() {
    UUID conversationId = UUID.randomUUID();
    Conversation conversation = new Conversation();
    conversation.setId(conversationId);
    when(conversations.find(conversationId)).thenReturn(conversation);
    when(messages.nextTurn(conversationId)).thenReturn(1);
    when(rag.getMaxQuestionChars()).thenReturn(2000);
    when(conversationContext.prepare(any(Conversation.class), eq(1), eq("你好")))
        .thenReturn(preparedSystemChat("你好"));
    when(chat.stream(anyString(), anyString(), any(), any()))
        .thenReturn(new ChatClient.Generation("你好，有什么可以帮你？", "chat", "test", "model"));
    runTransactionsWithResultImmediately();

    service.ask(conversationId, UUID.randomUUID(), "你好", "request-id");

    verify(chat, timeout(2000)).stream(anyString(), contains("你好"), any(), any());
    verify(messages, timeout(2000)).prepare(any(UUID.class), isNull(), eq("[]"));
    verifyNoInteractions(executionStage);
  }

  @Test
  void toolOnlyRouteGeneratesAnswerWithoutKnowledgeSources() {
    UUID conversationId = UUID.randomUUID();
    Conversation conversation = new Conversation();
    conversation.setId(conversationId);
    when(conversations.find(conversationId)).thenReturn(conversation);
    when(messages.nextTurn(conversationId)).thenReturn(1);
    when(rag.getMaxQuestionChars()).thenReturn(2000);
    ConversationContextService.PreparedContext prepared = preparedTool("查询今日排班");
    when(conversationContext.prepare(any(Conversation.class), eq(1), eq("查询今日排班")))
        .thenReturn(prepared);
    ToolObservation observation =
        new ToolObservation(
            "schedule.read",
            "{}",
            "test#schedule.read",
            ToolObservation.Status.SUCCESS,
            "TOOL_COMPLETED",
            "今天值班",
            false,
            1);
    ExecutionResult execution =
        new ExecutionResult(
            List.of(),
            List.of(
                new SubQuestionExecution(
                    "Q1",
                    IntentType.MCP_TOOL,
                    SubQuestionExecution.Status.SUCCESS,
                    List.of(),
                    observation,
                    "TOOL_COMPLETED",
                    1)),
            RagBudgetSnapshot.from(new RagProperties()));
    when(executionStage.execute(
            eq(prepared.queryPlan()),
            eq(prepared.routingPlan()),
            isNull(),
            any(CancellationToken.class)))
        .thenReturn(execution);
    when(deduplicationStage.execute(List.of(), execution.budget()))
        .thenReturn(new DeduplicationResult(List.of(), 0, 0, 0));
    when(rerankStage.execute(
            eq(prepared.queryPlan()), eq(execution), eq(List.of()), any(CancellationToken.class)))
        .thenReturn(
            new RerankResult(
                List.of(),
                List.of(),
                RerankResult.Status.EMPTY,
                "NO_RERANK_INPUT",
                null,
                null,
                null,
                null,
                0));
    when(chat.stream(anyString(), anyString(), any(), any()))
        .thenReturn(new ChatClient.Generation("根据工具 T1，今天值班", "chat", "test", "model"));
    runTransactionsWithResultImmediately();

    service.ask(conversationId, UUID.randomUUID(), "查询今日排班", "request-id");

    verify(chat, timeout(2000)).stream(
        anyString(), contains("\"referenceId\":\"T1\""), any(), any());
    verify(messages, timeout(2000)).prepare(any(UUID.class), eq("查询今日排班"), eq("[]"));
  }

  @Test
  void citationRepairInvalidatesFirstAttemptAndStreamsTheReplacement() {
    UUID conversationId = UUID.randomUUID();
    Conversation conversation = new Conversation();
    conversation.setId(conversationId);
    when(conversations.find(conversationId)).thenReturn(conversation);
    when(messages.nextTurn(conversationId)).thenReturn(1);
    when(rag.getMaxQuestionChars()).thenReturn(2000);
    when(config.getCheckpointChars()).thenReturn(1000);
    when(config.getCheckpointIntervalMs()).thenReturn(60_000L);
    when(conversationContext.prepare(any(Conversation.class), eq(1), eq("你好")))
        .thenReturn(preparedSystemChat("你好"));
    when(messages.markStreaming(any(UUID.class))).thenReturn(1);
    AiProperties.ModelTarget target =
        new AiProperties.ModelTarget(
            "chat", "test", "model", "http://localhost", "/chat", "", 1000, 0, false);
    when(chat.stream(anyString(), anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              ChatClient.StreamObserver observer = invocation.getArgument(2);
              observer.started(target, "PRIMARY");
              observer.delta("[S99]");
              observer.completed(target, "[S99]", "stop");
              return new ChatClient.Generation("[S99]", "chat", "test", "model");
            })
        .thenAnswer(
            invocation -> {
              ChatClient.StreamObserver observer = invocation.getArgument(2);
              observer.started(target, "PRIMARY");
              observer.delta("你好");
              observer.completed(target, "你好", "stop");
              return new ChatClient.Generation("你好", "chat", "test", "model");
            });
    runTransactionsWithResultImmediately();
    runTransactionsImmediately();

    service.ask(conversationId, UUID.randomUUID(), "你好", "request-id");

    verify(messages, timeout(2000).times(1)).markStreaming(any(UUID.class));
    verify(attempts, timeout(2000))
        .invalidateCompleted(any(UUID.class), eq("INVALID_CITATIONS"), anyString());
    verify(messages, timeout(2000)).checkpoint(any(UUID.class), eq(""));
    verify(messages, timeout(2000)).complete(any(UUID.class), eq("你好"), eq("[]"), anyString());
    ArgumentCaptor<GenerationAttempt> captured = ArgumentCaptor.forClass(GenerationAttempt.class);
    verify(attempts, timeout(2000).times(2)).insert(captured.capture());
    assertEquals(
        List.of("PRIMARY", "CITATION_REPAIR"),
        captured.getAllValues().stream().map(GenerationAttempt::getReason).toList());
  }

  private ConversationContextService.PreparedContext preparedMixed(String question) {
    QueryPlan plan =
        new QueryPlan(
            question, List.of(new SubQuestion("Q1", question), new SubQuestion("Q2", "你好")));
    RoutingPlan routing =
        new RoutingPlan(
            List.of(
                IntentRoute.knowledgeFallback("Q1", 1, RoutingReasonCode.KNOWLEDGE_SOURCE_REQUIRED),
                new IntentRoute(
                    "Q2",
                    IntentType.SYSTEM_CHAT,
                    1,
                    null,
                    Map.of(),
                    RoutingReasonCode.GENERAL_CHAT)));
    return new ConversationContextService.PreparedContext(emptyMemory(), plan, routing);
  }

  private ConversationContextService.PreparedContext preparedSystemChat(String question) {
    QueryPlan plan = QueryPlan.fallback(question);
    RoutingPlan routing =
        new RoutingPlan(
            List.of(
                new IntentRoute(
                    "Q1",
                    IntentType.SYSTEM_CHAT,
                    1,
                    null,
                    Map.of(),
                    RoutingReasonCode.GENERAL_CHAT)));
    return new ConversationContextService.PreparedContext(emptyMemory(), plan, routing);
  }

  private ConversationContextService.PreparedContext preparedTool(String question) {
    QueryPlan plan = QueryPlan.fallback(question);
    RoutingPlan routing =
        new RoutingPlan(
            List.of(
                new IntentRoute(
                    "Q1",
                    IntentType.MCP_TOOL,
                    1,
                    "schedule.read",
                    Map.of(),
                    RoutingReasonCode.EXTERNAL_SOURCE_REQUIRED)));
    return new ConversationContextService.PreparedContext(emptyMemory(), plan, routing);
  }

  private com.hnu.backend.rag.memory.RagMemory emptyMemory() {
    return new com.hnu.backend.rag.memory.RagMemory("{}", 0, List.of(), List.of(), 0);
  }

  private Message assistant(UUID conversationId, UUID generationId, String status) {
    Message message = new Message();
    message.setId(generationId);
    message.setConversationId(conversationId);
    message.setRole("ASSISTANT");
    message.setStatus(status);
    message.setContent("partial");
    return message;
  }

  private void runTransactionsImmediately() {
    doAnswer(
            invocation -> {
              Consumer<TransactionStatus> callback = invocation.getArgument(0);
              callback.accept(mock(TransactionStatus.class));
              return null;
            })
        .when(tx)
        .executeWithoutResult(any());
  }

  private void runTransactionsWithResultImmediately() {
    when(tx.execute(any()))
        .thenAnswer(
            invocation -> {
              TransactionCallback<?> callback = invocation.getArgument(0);
              return callback.doInTransaction(mock(TransactionStatus.class));
            });
  }
}
