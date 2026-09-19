package com.hnu.backend.knowledgebase.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.document.service.DocumentCleanupService;
import com.hnu.backend.intent.IntentTreeChangedEvent;
import com.hnu.backend.knowledgebase.entity.KnowledgeBase;
import com.hnu.backend.knowledgebase.mapper.KnowledgeBaseMapper;
import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.shared.error.ApiException;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class KnowledgeBaseServiceTest {
  @Test
  void hidesAliyunCandidateUntilItsApiKeyIsConfigured() {
    AiProperties ai = models("");
    KnowledgeBaseService knowledgeBaseService = service(ai);
    assertEquals(
        java.util.List.of("qwen-emb-8b"),
        knowledgeBaseService.embeddingModels().stream().map(model -> model.id()).toList());

    ai.getProviders().get("bailian").setApiKey("test-only");
    assertEquals(
        java.util.List.of("qwen-emb-8b", "bailian-qwen3.7-embedding"),
        knowledgeBaseService.embeddingModels().stream().map(model -> model.id()).toList());
  }

  @Test
  void sameNameAndDimensionsDoNotPermitChangedProviderOrConfigId() {
    AiProperties ai = models("test-only");
    KnowledgeBaseService knowledgeBaseService = service(ai);
    KnowledgeBase kb = new KnowledgeBase();
    kb.setEmbeddingModelId("qwen-emb-8b");
    kb.setEmbeddingProvider("siliconflow");
    kb.setEmbeddingModel("same-model");
    kb.setEmbeddingDimensions(1536);

    ApiException changed =
        assertThrows(
            ApiException.class,
            () ->
                knowledgeBaseService.checkModel(
                    kb, "bailian-qwen3.7-embedding", "bailian", "same-model", 1536));
    assertEquals("EMBEDDING_MODEL_CHANGED", changed.code());
    ApiException removed =
        assertThrows(
            ApiException.class,
            () ->
                knowledgeBaseService.checkModel(kb, "removed", "siliconflow", "same-model", 1536));
    assertEquals("EMBEDDING_MODEL_CHANGED", removed.code());
    ai.getEmbedding().getCandidates().getFirst().setProvider("bailian");
    ApiException overwritten =
        assertThrows(
            ApiException.class,
            () ->
                knowledgeBaseService.checkModel(
                    kb, "qwen-emb-8b", "siliconflow", "same-model", 1536));
    assertEquals("EMBEDDING_BINDING_CHANGED", overwritten.code());
    ai.getEmbedding().getCandidates().removeFirst();
    ApiException unavailable =
        assertThrows(
            ApiException.class,
            () ->
                knowledgeBaseService.checkModel(
                    kb, "qwen-emb-8b", "siliconflow", "same-model", 1536));
    assertEquals("EMBEDDING_MODEL_UNAVAILABLE", unavailable.code());
  }

  @Test
  void deletingKnowledgeBasePublishesIntentSnapshotInvalidationAfterDatabaseWork() {
    KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
    DocumentCleanupService cleanup = mock(DocumentCleanupService.class);
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    KnowledgeBaseService service =
        new KnowledgeBaseService(knowledgeBases, cleanup, transactions, models(""), events);
    UUID id = UUID.randomUUID();
    when(knowledgeBases.findAdminOwned(id)).thenReturn(new KnowledgeBase());
    doAnswer(
            invocation -> {
              Consumer<TransactionStatus> callback = invocation.getArgument(0);
              callback.accept(mock(TransactionStatus.class));
              return null;
            })
        .when(transactions)
        .executeWithoutResult(any());

    service.delete(id);

    verify(knowledgeBases).deleteById(id);
    verify(events).publishEvent(any(IntentTreeChangedEvent.class));
  }

  private KnowledgeBaseService service(AiProperties ai) {
    return new KnowledgeBaseService(
        mock(KnowledgeBaseMapper.class),
        mock(DocumentCleanupService.class),
        mock(TransactionTemplate.class),
        ai,
        mock(ApplicationEventPublisher.class));
  }

  private AiProperties models(String aliyunKey) {
    AiProperties ai = new AiProperties();
    provider(ai, "siliconflow", "test-only");
    provider(ai, "bailian", aliyunKey);
    candidate(ai, "qwen-emb-8b", "siliconflow");
    candidate(ai, "bailian-qwen3.7-embedding", "bailian");
    ai.getEmbedding().setDefaultModel("qwen-emb-8b");
    return ai;
  }

  private void provider(AiProperties ai, String id, String key) {
    AiProperties.Provider provider = new AiProperties.Provider();
    provider.setUrl("https://example.com");
    provider.setApiKey(key);
    provider.getEndpoints().setEmbedding("/v1/embeddings");
    ai.getProviders().put(id, provider);
  }

  private void candidate(AiProperties ai, String id, String provider) {
    AiProperties.Candidate candidate = new AiProperties.Candidate();
    candidate.setId(id);
    candidate.setProvider(provider);
    candidate.setModel("same-model");
    candidate.setDimension(1536);
    ai.getEmbedding().getCandidates().add(candidate);
  }
}
