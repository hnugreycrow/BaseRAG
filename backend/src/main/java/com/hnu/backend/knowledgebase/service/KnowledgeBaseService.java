package com.hnu.backend.knowledgebase.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

  /**
   * 创建知识库服务。
   *
   * @param mapper 知识库持久化接口
   * @param documentCleanup 关联文档清理服务
   * @param tx 事务模板
   * @param ai 模型配置
   */
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

  /**
   * 分页查询当前用户的知识库，并附带文档数量。
   *
   * @param ownerId 所属用户标识
   * @param page 页码
   * @param pageSize 每页数量
   * @param rawQuery 可选搜索词
   * @return 知识库分页
   */
  public PageResponse<KnowledgeBaseResponse> list(
      UUID ownerId, int page, int pageSize, String rawQuery) {
    String query = normalizeQuery(rawQuery);
    long total = mapper.countWithDocumentCount(ownerId, query);
    List<KnowledgeBaseResponse> items =
        mapper.selectWithDocumentCount(ownerId, query, pageSize, offset(page, pageSize)).stream()
            .map(this::toResponse)
            .toList();
    return PageResponse.of(items, total, page, pageSize);
  }

  /**
   * 获取指定知识库的接口响应对象。
   *
   * @param ownerId 所属用户标识
   * @param id 知识库标识
   * @return 知识库响应
   */
  public KnowledgeBaseResponse get(UUID ownerId, UUID id) {
    return toResponse(requireEntity(ownerId, id));
  }

  /**
   * 获取知识库实体，不存在时抛出统一的业务异常。
   *
   * @param ownerId 所属用户标识
   * @param id 知识库标识
   * @return 持久化实体
   */
  public KnowledgeBase requireEntity(UUID ownerId, UUID id) {
    KnowledgeBase kb =
        mapper.selectOne(
            Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getOwnerId, ownerId));
    if (kb == null)
      throw new ApiException("KNOWLEDGE_BASE_NOT_FOUND", "知识库不存在", HttpStatus.NOT_FOUND);
    return kb;
  }

  /**
   * 创建知识库，并将其绑定到配置中明确选择的向量模型。
   *
   * @param ownerId 所属用户标识
   * @param rawName 原始知识库名称
   * @param embeddingModelId Embedding 模型配置标识
   * @return 新知识库
   */
  public KnowledgeBaseResponse create(UUID ownerId, String rawName, String embeddingModelId) {
    AiProperties.ModelTarget model;
    try {
      model = ai.embeddingModel(embeddingModelId);
    } catch (IllegalArgumentException e) {
      throw ApiException.bad("INVALID_EMBEDDING_MODEL", "请选择配置文件中可用的向量模型");
    }
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(UUID.randomUUID());
    kb.setOwnerId(ownerId);
    kb.setName(normalizeName(rawName));
    kb.setEmbeddingModel(model.model());
    kb.setEmbeddingDimensions(model.dimension());
    mapper.insert(kb);
    return get(ownerId, kb.getId());
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
   * @param ownerId 所属用户标识
   * @param id 知识库标识
   * @return 已绑定模型的知识库实体
   */
  public KnowledgeBase ensureModel(UUID ownerId, UUID id) {
    KnowledgeBase kb = requireEntity(ownerId, id);
    if (kb.getEmbeddingModel() != null) return kb;
    AiProperties.ModelTarget model = ai.embeddingModel();
    return tx.execute(status -> lockAndBindModel(ownerId, id, model.model(), model.dimension()));
  }

  /**
   * 修改知识库名称。
   *
   * @param ownerId 所属用户标识
   * @param id 知识库标识
   * @param rawName 新名称
   * @return 更新后的知识库
   */
  public KnowledgeBaseResponse rename(UUID ownerId, UUID id, String rawName) {
    KnowledgeBase kb = requireEntity(ownerId, id);
    kb.setName(normalizeName(rawName));
    mapper.updateById(kb);
    return get(ownerId, id);
  }

  /**
   * 删除知识库及其关联数据，并在数据库事务提交后尽力清理对象存储文件。
   *
   * <p>存储清理失败只记录日志，避免把已提交的数据库删除误报为整体失败。
   *
   * @param ownerId 所属用户标识
   * @param id 知识库标识
   */
  public void delete(UUID ownerId, UUID id) {
    requireEntity(ownerId, id);
    List<String> storageKeys = documentCleanup.storageKeys(id);
    tx.executeWithoutResult(
        status -> {
          documentCleanup.deleteRecords(id);
          mapper.deleteById(id);
        });
    documentCleanup.removeStoredFiles(storageKeys);
  }

  /**
   * 规范化并校验知识库名称。
   *
   * @param rawName 原始名称
   * @return 合法名称
   */
  private String normalizeName(String rawName) {
    String name = rawName == null ? "" : rawName.trim();
    if (name.isEmpty() || name.length() > 200 || name.chars().anyMatch(Character::isISOControl))
      throw ApiException.bad("INVALID_KNOWLEDGE_BASE_NAME", "知识库名称应为 1 到 200 个有效字符");
    return name;
  }

  /**
   * 规范化可选搜索词。
   *
   * @param rawQuery 原始搜索词
   * @return 搜索词；空白输入返回 {@code null}
   */
  private String normalizeQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isBlank()) return null;
    return rawQuery.trim();
  }

  /**
   * 校验分页参数并计算偏移。
   *
   * @param page 页码
   * @param pageSize 每页数量
   * @return 行偏移
   */
  private long offset(int page, int pageSize) {
    if (page < 1 || pageSize < 1 || pageSize > 100)
      throw ApiException.bad("INVALID_PAGE", "页码应大于 0，每页数量应为 1 到 100");
    return (long) (page - 1) * pageSize;
  }

  /**
   * 校验知识库绑定的模型及维度是否与本次处理使用的配置一致。
   *
   * @param kb 知识库
   * @param model 本次模型名称
   * @param dimensions 本次向量维度
   */
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
   * @param ownerId 所属用户标识
   * @param id 知识库标识
   * @param model 模型名称
   * @param dimensions 向量维度
   * @return 锁定并校验后的知识库实体
   */
  public KnowledgeBase lockAndBindModel(UUID ownerId, UUID id, String model, int dimensions) {
    KnowledgeBase kb = mapper.lock(ownerId, id);
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

  /**
   * 转换知识库响应。
   *
   * @param knowledgeBase 知识库实体
   * @return 安全响应
   */
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
