package com.hnu.backend.knowledgebase.service;

import com.hnu.backend.document.service.DocumentCleanupService;
import com.hnu.backend.knowledgebase.entity.KnowledgeBase;
import com.hnu.backend.knowledgebase.mapper.KnowledgeBaseMapper;
import com.hnu.backend.knowledgebase.vo.EmbeddingModelResponse;
import com.hnu.backend.knowledgebase.vo.KnowledgeBaseResponse;
import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.web.PageResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 处理知识库生命周期及其向量模型绑定规则。 */
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

  /** 分页查询知识库，并附带文档数量。 */
  public PageResponse<KnowledgeBaseResponse> list(int page, int pageSize, String rawQuery) {
    String query = normalizeQuery(rawQuery);
    long total = mapper.countWithDocumentCount(query);
    List<KnowledgeBaseResponse> items =
        mapper.selectWithDocumentCount(query, pageSize, offset(page, pageSize)).stream()
            .map(this::toResponse)
            .toList();
    return PageResponse.of(items, total, page, pageSize);
  }

  /** 获取指定知识库的接口响应对象。 */
  public KnowledgeBaseResponse get(UUID id) {
    return toResponse(requireEntity(id));
  }

  /**
   * 获取知识库实体，不存在时抛出统一的业务异常。
   *
   * @param id 知识库标识
   * @return 持久化实体
   */
  public KnowledgeBase requireEntity(UUID id) {
    KnowledgeBase kb = mapper.selectById(id);
    if (kb == null)
      throw new ApiException("KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在", HttpStatus.NOT_FOUND);
    return kb;
  }

  /** 创建知识库，并将其绑定到配置中明确选择的向量模型。 */
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

  /** 返回可用于新建知识库的向量模型配置。 */
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

  /**
   * 确保知识库已绑定向量模型；对模型选择功能上线前创建的数据，原子地补绑默认模型。
   *
   * @param id 知识库标识
   * @return 已绑定模型的知识库实体
   */
  public KnowledgeBase ensureModel(UUID id) {
    KnowledgeBase kb = requireEntity(id);
    if (kb.getEmbeddingModel() != null) return kb;
    AiProperties.ModelTarget model = ai.embeddingModel();
    return tx.execute(status -> lockAndBindModel(id, model.model(), model.dimension()));
  }

  /** 修改知识库名称。 */
  public KnowledgeBaseResponse rename(UUID id, String rawName) {
    KnowledgeBase kb = requireEntity(id);
    kb.setName(normalizeName(rawName));
    mapper.updateById(kb);
    return get(id);
  }

  /**
   * 删除知识库及其关联数据，并在数据库事务提交后尽力清理对象存储文件。
   *
   * <p>存储清理失败只记录日志，避免把已提交的数据库删除误报为整体失败。
   */
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

  private String normalizeQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isBlank()) return null;
    return rawQuery.trim();
  }

  private long offset(int page, int pageSize) {
    if (page < 1 || pageSize < 1 || pageSize > 100)
      throw ApiException.bad("INVALID_PAGE", "页码应大于 0，每页数量应为 1 到 100");
    return (long) (page - 1) * pageSize;
  }

  /** 校验知识库绑定的模型及维度是否与本次处理使用的配置一致。 */
  public void checkModel(KnowledgeBase kb, String model, int dimensions) {
    if (kb.getEmbeddingModel() != null
        && (!kb.getEmbeddingModel().equals(model) || kb.getEmbeddingDimensions() != dimensions)) {
      throw new ApiException(
          "EMBEDDING_MODEL_CHANGED", "Embedding 模型或维度已变更，请恢复原配置或显式重建知识库", HttpStatus.CONFLICT);
    }
  }

  /**
   * 锁定知识库记录并完成首次模型绑定，防止并发导入写入不同维度的向量。
   *
   * @return 锁定并校验后的知识库实体
   */
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
