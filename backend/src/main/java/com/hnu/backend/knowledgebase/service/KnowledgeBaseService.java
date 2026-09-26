package com.hnu.backend.knowledgebase.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hnu.backend.knowledgebase.api.KnowledgeBaseAccess;
import com.hnu.backend.knowledgebase.api.KnowledgeBaseRemoval;
import com.hnu.backend.knowledgebase.entity.KnowledgeBase;
import com.hnu.backend.knowledgebase.mapper.KnowledgeBaseMapper;
import com.hnu.backend.knowledgebase.vo.EmbeddingModelResponse;
import com.hnu.backend.knowledgebase.vo.KnowledgeBaseResponse;
import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import com.hnu.backend.shared.web.PageResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 处理知识库生命周期及其向量模型绑定规则。 */
@Service
public class KnowledgeBaseService implements KnowledgeBaseAccess, KnowledgeBaseRemoval {
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final TransactionTemplate tx;
  private final AiProperties ai;

  /**
   * 创建知识库服务。
   *
   * @param knowledgeBaseMapper 知识库持久化接口
   * @param tx 事务模板
   * @param ai 模型配置
   */
  public KnowledgeBaseService(
      KnowledgeBaseMapper knowledgeBaseMapper, TransactionTemplate tx, AiProperties ai) {
    this.knowledgeBaseMapper = knowledgeBaseMapper;
    this.tx = tx;
    this.ai = ai;
  }

  /**
   * 分页查询所有管理员创建的公共知识库，并附带文档数量。
   *
   * @param page 页码
   * @param pageSize 每页数量
   * @param rawQuery 可选搜索词
   * @return 知识库分页
   */
  public PageResponse<KnowledgeBaseResponse> list(int page, int pageSize, String rawQuery) {
    String query = normalizeQuery(rawQuery);
    long total = knowledgeBaseMapper.countWithDocumentCount(query);
    List<KnowledgeBaseResponse> items =
        knowledgeBaseMapper
            .selectWithDocumentCount(query, pageSize, offset(page, pageSize))
            .stream()
            .map(this::toResponse)
            .toList();
    return PageResponse.of(items, total, page, pageSize);
  }

  /**
   * 获取管理员创建的指定公共知识库。
   *
   * @param id 知识库标识
   * @return 知识库响应
   */
  public KnowledgeBaseResponse get(UUID id) {
    return toResponse(requireAdminOwned(id));
  }

  /**
   * 获取知识库实体，不存在时抛出统一的业务异常。
   *
   * @param ownerId 所属用户标识
   * @param id 知识库标识
   * @return 持久化实体
   */
  private KnowledgeBase requireEntity(UUID ownerId, UUID id) {
    KnowledgeBase kb =
        knowledgeBaseMapper.selectOne(
            Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getOwnerId, ownerId));
    if (kb == null) {
      throw new ApiException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在");
    }
    return kb;
  }

  /**
   * 查找管理员创建的公共知识库；内部处理仍使用记录中的创建者标识。
   *
   * @param id 知识库标识
   * @return 公共知识库实体
   */
  private KnowledgeBase requireAdminOwned(UUID id) {
    KnowledgeBase kb = knowledgeBaseMapper.findAdminOwned(id);
    if (kb == null) {
      throw new ApiException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在");
    }
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
      throw ApiException.bad(ErrorCode.INVALID_EMBEDDING_MODEL, "请选择配置文件中可用的向量模型");
    }
    requireAvailable(model);
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(UUID.randomUUID());
    kb.setOwnerId(ownerId);
    kb.setName(normalizeName(rawName));
    kb.setEmbeddingModelId(model.id());
    kb.setEmbeddingProvider(model.provider());
    kb.setEmbeddingModel(model.model());
    kb.setEmbeddingDimensions(model.dimension());
    knowledgeBaseMapper.insert(kb);
    return get(kb.getId());
  }

  /** 返回可用于新建知识库的向量模型配置。 */
  public List<EmbeddingModelResponse> embeddingModels() {
    String defaultId = ai.getEmbedding().getDefaultModel();
    return ai.embeddingModels().stream()
        .filter(model -> model.apiKey() != null && !model.apiKey().isBlank())
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
  private KnowledgeBase ensureModel(UUID ownerId, UUID id) {
    KnowledgeBase kb = requireEntity(ownerId, id);
    if (kb.getEmbeddingModel() != null) {
      checkModel(
          kb,
          kb.getEmbeddingModelId(),
          kb.getEmbeddingProvider(),
          kb.getEmbeddingModel(),
          kb.getEmbeddingDimensions());
      return kb;
    }
    AiProperties.ModelTarget model = ai.embeddingModel();
    requireAvailable(model);
    return tx.execute(
        status ->
            lockAndBindModel(
                ownerId, id, model.id(), model.provider(), model.model(), model.dimension()));
  }

  /**
   * 修改公共知识库名称。
   *
   * @param id 知识库标识
   * @param rawName 新名称
   * @return 更新后的知识库
   */
  public KnowledgeBaseResponse rename(UUID id, String rawName) {
    KnowledgeBase kb = requireAdminOwned(id);
    kb.setName(normalizeName(rawName));
    knowledgeBaseMapper.updateById(kb);
    return get(id);
  }

  /** {@inheritDoc} */
  @Override
  public void deleteRecord(UUID id) {
    if (id == null || knowledgeBaseMapper.deleteById(id) != 1) {
      throw new ApiException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在");
    }
  }

  /** {@inheritDoc} */
  @Override
  public UUID requireManagedOwner(UUID id) {
    return requireAdminOwned(id).getOwnerId();
  }

  /** {@inheritDoc} */
  @Override
  public void requireOwned(UUID ownerId, UUID id) {
    requireEntity(ownerId, id);
  }

  /** {@inheritDoc} */
  @Override
  public EmbeddingBinding ensureEmbedding(UUID ownerId, UUID id) {
    KnowledgeBase kb = ensureModel(ownerId, id);
    return new EmbeddingBinding(
        kb.getEmbeddingModelId(),
        kb.getEmbeddingProvider(),
        kb.getEmbeddingModel(),
        kb.getEmbeddingDimensions());
  }

  /** {@inheritDoc} */
  @Override
  public void lockAndBind(
      UUID ownerId, UUID id, String modelId, String provider, String model, int dimensions) {
    lockAndBindModel(ownerId, id, modelId, provider, model, dimensions);
  }

  /**
   * 规范化并校验知识库名称。
   *
   * @param rawName 原始名称
   * @return 合法名称
   */
  private String normalizeName(String rawName) {
    String name = rawName == null ? "" : rawName.trim();
    if (name.isEmpty() || name.length() > 200 || name.chars().anyMatch(Character::isISOControl)) {
      throw ApiException.bad(ErrorCode.INVALID_KNOWLEDGE_BASE_NAME, "知识库名称应为 1 到 200 个有效字符");
    }
    return name;
  }

  /**
   * 规范化可选搜索词。
   *
   * @param rawQuery 原始搜索词
   * @return 搜索词；空白输入返回 {@code null}
   */
  private String normalizeQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isBlank()) {
      return null;
    }
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
    if (page < 1 || pageSize < 1 || pageSize > 100) {
      throw ApiException.bad(ErrorCode.INVALID_PAGE, "页码应大于 0，每页数量应为 1 到 100");
    }
    return (long) (page - 1) * pageSize;
  }

  /**
   * 校验知识库绑定的模型及维度是否与本次处理使用的配置一致。
   *
   * @param kb 知识库
   * @param modelId 模型配置标识
   * @param provider 供应商标识
   * @param model 本次模型名称
   * @param dimensions 本次向量维度
   */
  void checkModel(KnowledgeBase kb, String modelId, String provider, String model, int dimensions) {
    if (kb.getEmbeddingModel() != null
        && (!kb.getEmbeddingModelId().equals(modelId)
            || !kb.getEmbeddingProvider().equals(provider)
            || !kb.getEmbeddingModel().equals(model)
            || kb.getEmbeddingDimensions() != dimensions)) {
      throw new ApiException(ErrorCode.EMBEDDING_MODEL_CHANGED, "Embedding 供应商、模型或维度已变更，请恢复原配置");
    }
    AiProperties.ModelTarget configured;
    try {
      configured = ai.embeddingModel(modelId);
    } catch (IllegalArgumentException error) {
      throw new ApiException(ErrorCode.EMBEDDING_MODEL_UNAVAILABLE, "知识库绑定的向量模型已不在配置中");
    }
    if (!configured.provider().equals(provider)
        || !configured.model().equals(model)
        || configured.dimension() != dimensions) {
      throw new ApiException(ErrorCode.EMBEDDING_BINDING_CHANGED, "向量模型配置已变更，请恢复原供应商、模型和维度");
    }
  }

  /**
   * 锁定知识库记录并完成首次模型绑定，防止并发导入写入不同维度的向量。
   *
   * @param ownerId 所属用户标识
   * @param id 知识库标识
   * @param modelId 模型配置标识
   * @param provider 供应商标识
   * @param model 模型名称
   * @param dimensions 向量维度
   * @return 锁定并校验后的知识库实体
   */
  private KnowledgeBase lockAndBindModel(
      UUID ownerId, UUID id, String modelId, String provider, String model, int dimensions) {
    KnowledgeBase kb = knowledgeBaseMapper.lock(ownerId, id);
    if (kb == null) {
      throw new ApiException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在");
    }
    checkModel(kb, modelId, provider, model, dimensions);
    if (kb.getEmbeddingModel() == null) {
      kb.setEmbeddingModelId(modelId);
      kb.setEmbeddingProvider(provider);
      kb.setEmbeddingModel(model);
      kb.setEmbeddingDimensions(dimensions);
      knowledgeBaseMapper.updateById(kb);
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
        knowledgeBase.getEmbeddingModelId(),
        knowledgeBase.getEmbeddingProvider(),
        knowledgeBase.getEmbeddingDimensions(),
        knowledgeBase.getDocumentCount() == null ? 0 : knowledgeBase.getDocumentCount(),
        knowledgeBase.getCreatedAt());
  }

  private void requireAvailable(AiProperties.ModelTarget model) {
    if (model.apiKey() == null || model.apiKey().isBlank()) {
      throw ApiException.bad(ErrorCode.MODEL_NOT_CONFIGURED, "所选向量模型未配置 API Key");
    }
  }
}
