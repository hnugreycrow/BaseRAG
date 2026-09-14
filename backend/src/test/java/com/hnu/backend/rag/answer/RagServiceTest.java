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
  private final RagProperties config = new RagProperties();
  private final RetrievalService retrieval = mock(RetrievalService.class);
  private final ChatClient chat = mock(ChatClient.class);
  private final ContextBuilder contexts = new ContextBuilder(config);
  private final RagService service =
      new RagService(retrieval, contexts, new PromptAssemblyStage(contexts), chat, config);

  @Test
  void emptyRetrievalDoesNotCallGeneration() {
    when(retrieval.retrieve("问题")).thenReturn(List.of());
    var result = service.ask("问题");
    assertTrue(result.answer().contains("资料不足"));
    assertTrue(result.sources().isEmpty());
    verifyNoInteractions(chat);
  }

  @Test
  void repairsInvalidCitationOnceAndReturnsActualContext() {
    var hit = ContextAndCitationsTest.hit("员工年假为五天。");
    when(retrieval.retrieve("年假？")).thenReturn(List.of(hit));
    when(chat.generate(anyString(), anyString()))
        .thenReturn(generation("五天 [S99]"), generation("五天 [S1]"));
    var result = service.ask("年假？");
    assertEquals(List.of("S1"), result.citations());
    assertEquals(hit.getContent(), result.sources().getFirst().content());
    assertEquals("qwen-plus-latest", result.modelInfo().model());
    verify(chat, times(2)).generate(anyString(), contains(hit.getContent()));
  }

  @Test
  void secondIllegalCitationFailsInsteadOfReturningAnswer() {
    when(retrieval.retrieve(anyString())).thenReturn(List.of(ContextAndCitationsTest.hit("依据")));
    when(chat.generate(anyString(), anyString())).thenReturn(generation("[S99]"));
    assertThrows(ApiException.class, () -> service.ask("问题"));
    verify(chat, times(2)).generate(anyString(), anyString());
  }

  @Test
  void rejectsEmptyAndOverBudgetQuestions() {
    assertThrows(ApiException.class, () -> service.ask(" "));
    assertThrows(ApiException.class, () -> service.ask("甲".repeat(2001)));
    verifyNoInteractions(retrieval, chat);
  }

  @Test
  void passesRequestedKnowledgeBaseScopeToRetrieval() {
    UUID knowledgeBaseId = UUID.randomUUID();
    when(retrieval.retrieve("年假？", List.of(knowledgeBaseId))).thenReturn(List.of());

    service.ask("年假？", List.of(knowledgeBaseId));

    verify(retrieval).retrieve("年假？", List.of(knowledgeBaseId));
    verify(retrieval, never()).retrieve("年假？");
  }

  private ChatClient.Generation generation(String content) {
    return new ChatClient.Generation(content, "qwen-plus", "bailian", "qwen-plus-latest");
  }
}
