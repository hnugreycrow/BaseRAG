package com.hnu.backend.knowledgebase.application;

import com.hnu.backend.ai.config.AiProperties;
import com.hnu.backend.document.application.DocumentCleanupService;
import com.hnu.backend.knowledgebase.api.EmbeddingModelResponse;
import com.hnu.backend.knowledgebase.api.KnowledgeBaseResponse;
import com.hnu.backend.knowledgebase.domain.KnowledgeBase;
import com.hnu.backend.knowledgebase.infrastructure.persistence.KnowledgeBaseMapper;
import com.hnu.backend.shared.error.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KnowledgeBaseService {
  private final KnowledgeBaseMapper mapper;
  private final DocumentCleanupService documentCleanup;
  private final TransactionTemplate tx;
  private final AiProperties ai;

  public KnowledgeBaseService(
      KnowledgeBaseMapper mapper,
      DocumentCleanupService documentCleanup,
      TransactionTemplate tx,
      AiProperties ai) {
    this.mapper = mapper;
    this.documentCleanup = documentCleanup;
    this.tx = tx;
    this.ai = ai;
  }

  public List<KnowledgeBaseResponse> list() {
    return mapper.selectWithDocumentCount().stream().map(this::toResponse).toList();
  }

  public KnowledgeBaseResponse get(UUID id) {
    return toResponse(requireEntity(id));
  }

  public KnowledgeBase requireEntity(UUID id) {
    KnowledgeBase kb = mapper.selectById(id);
    if (kb == null)
      throw new ApiException("KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在", HttpStatus.NOT_FOUND);
    return kb;
  }

  public KnowledgeBaseResponse create(String rawName, String embeddingModelId) {
    AiProperties.ModelTarget model;
    try {
      model = ai.embeddingModel(embeddingModelId);
    } catch (IllegalArgumentException e) {
      throw ApiException.bad("INVALID_EMBEDDING_MODEL", "请选择配置文件中可用的向量模型");
    }
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(UUID.randomUUID());
    kb.setName(normalizeName(rawName));
    kb.setEmbeddingModel(model.model());
    kb.setEmbeddingDimensions(model.dimension());
    mapper.insert(kb);
    return get(kb.getId());
  }

  public List<EmbeddingModelResponse> embeddingModels() {
    String defaultId = ai.getEmbedding().getDefaultModel();
    return ai.embeddingModels().stream()
        .map(
            model ->
                new EmbeddingModelResponse(
                    model.id(),
                    model.provider(),
                    model.model(),
                    model.dimension(),
                    model.id().equals(defaultId)))
        .toList();
  }

  /** Binds databases created before model selection was introduced to the configured default. */
  public KnowledgeBase ensureModel(UUID id) {
    KnowledgeBase kb = requireEntity(id);
    if (kb.getEmbeddingModel() != null) return kb;
    AiProperties.ModelTarget model = ai.embeddingModel();
    return tx.execute(status -> lockAndBindModel(id, model.model(), model.dimension()));
  }

  public KnowledgeBaseResponse rename(UUID id, String rawName) {
    KnowledgeBase kb = requireEntity(id);
    kb.setName(normalizeName(rawName));
    mapper.updateById(kb);
    return get(id);
  }

  public void delete(UUID id) {
    requireEntity(id);
    List<String> storageKeys = documentCleanup.storageKeys(id);
    tx.executeWithoutResult(
        status -> {
          documentCleanup.deleteRecords(id);
          mapper.deleteById(id);
        });
    documentCleanup.removeStoredFiles(storageKeys);
  }

  private String normalizeName(String rawName) {
    String name = rawName == null ? "" : rawName.trim();
    if (name.isEmpty() || name.length() > 200 || name.chars().anyMatch(Character::isISOControl))
      throw ApiException.bad("INVALID_KNOWLEDGE_BASE_NAME", "知识库名称应为 1 到 200 个有效字符");
    return name;
  }

  public void checkModel(KnowledgeBase kb, String model, int dimensions) {
    if (kb.getEmbeddingModel() != null
        && (!kb.getEmbeddingModel().equals(model) || kb.getEmbeddingDimensions() != dimensions)) {
      throw new ApiException(
          "EMBEDDING_MODEL_CHANGED", "Embedding 模型或维度已变更，请恢复原配置或显式重建知识库", HttpStatus.CONFLICT);
    }
  }

  public KnowledgeBase lockAndBindModel(UUID id, String model, int dimensions) {
    KnowledgeBase kb = mapper.lock(id);
    if (kb == null)
      throw new ApiException("KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在", HttpStatus.NOT_FOUND);
    checkModel(kb, model, dimensions);
    if (kb.getEmbeddingModel() == null) {
      kb.setEmbeddingModel(model);
      kb.setEmbeddingDimensions(dimensions);
      mapper.updateById(kb);
    }
    return kb;
  }

  private KnowledgeBaseResponse toResponse(KnowledgeBase knowledgeBase) {
    return new KnowledgeBaseResponse(
        knowledgeBase.getId(),
        knowledgeBase.getName(),
        knowledgeBase.getEmbeddingModel(),
        knowledgeBase.getEmbeddingDimensions(),
        knowledgeBase.getDocumentCount() == null ? 0 : knowledgeBase.getDocumentCount(),
        knowledgeBase.getCreatedAt());
  }
}
