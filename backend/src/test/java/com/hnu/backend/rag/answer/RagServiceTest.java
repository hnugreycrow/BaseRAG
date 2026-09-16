package com.hnu.backend.rag.answer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.rag.prompt.PromptAssemblyStage;
import com.hnu.backend.rag.retrieval.RetrievalService;
import com.hnu.backend.shared.error.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;

class RagServiceTest {
  private final UUID ownerId = UUID.randomUUID();
  private final RagProperties config = new RagProperties();
  private final RetrievalService retrievalService = mock(RetrievalService.class);
  private final ChatClient chat = mock(ChatClient.class);
  private final ContextBuilder contexts = new ContextBuilder(config);
  private final PromptAssemblyStage prompts = new PromptAssemblyStage(contexts);
  private final RagService ragService =
      new RagService(
          retrievalService,
          contexts,
          prompts,
          new AnswerStage(new ChatAnswerGenerator(chat), prompts),
          config);

  @Test
  void emptyRetrievalDoesNotCallGeneration() {
    when(retrievalService.retrieve(ownerId, "问题")).thenReturn(List.of());
    var result = ragService.ask(ownerId, "问题");
    assertTrue(result.answer().contains("资料不足"));
    assertTrue(result.sources().isEmpty());
    verifyNoInteractions(chat);
  }

  @Test
  void repairsInvalidCitationOnceAndReturnsActualContext() {
    var hit = ContextAndCitationsTest.hit("员工年假为五天。");
    when(retrievalService.retrieve(ownerId, "年假？")).thenReturn(List.of(hit));
    when(chat.stream(anyString(), anyString(), any(), any()))
        .thenReturn(generation("五天 [S99]"), generation("五天 [S1]"));
    var result = ragService.ask(ownerId, "年假？");
    assertEquals(List.of("S1"), result.citations());
    assertEquals(hit.getContent(), result.sources().getFirst().content());
    assertEquals("qwen-plus-latest", result.modelInfo().model());
    verify(chat, times(2)).stream(anyString(), contains(hit.getContent()), any(), any());
  }

  @Test
  void secondIllegalCitationFailsInsteadOfReturningAnswer() {
    when(retrievalService.retrieve(eq(ownerId), anyString()))
        .thenReturn(List.of(ContextAndCitationsTest.hit("依据")));
    when(chat.stream(anyString(), anyString(), any(), any())).thenReturn(generation("[S99]"));
    assertThrows(ApiException.class, () -> ragService.ask(ownerId, "问题"));
    verify(chat, times(2)).stream(anyString(), anyString(), any(), any());
  }

  @Test
  void rejectsEmptyAndOverBudgetQuestions() {
    assertThrows(ApiException.class, () -> ragService.ask(ownerId, " "));
    assertThrows(ApiException.class, () -> ragService.ask(ownerId, "甲".repeat(2001)));
    verifyNoInteractions(retrievalService, chat);
  }

  @Test
  void passesRequestedKnowledgeBaseScopeToRetrieval() {
    UUID knowledgeBaseId = UUID.randomUUID();
    when(retrievalService.retrieve(ownerId, "年假？", List.of(knowledgeBaseId))).thenReturn(List.of());

    ragService.ask(ownerId, "年假？", List.of(knowledgeBaseId));

    verify(retrievalService).retrieve(ownerId, "年假？", List.of(knowledgeBaseId));
    verify(retrievalService, never()).retrieve(ownerId, "年假？");
  }

  private ChatClient.Generation generation(String content) {
    return new ChatClient.Generation(content, "qwen-plus", "bailian", "qwen-plus-latest");
  }
}
