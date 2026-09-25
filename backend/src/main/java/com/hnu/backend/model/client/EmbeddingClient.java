package com.hnu.backend.model.client;

import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.model.config.EmbeddingProtocol;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import java.util.*;
import org.springframework.stereotype.Component;

/** 根据固定模型身份路由到对应 Embedding 协议适配器。 */
@Component
public class EmbeddingClient {
  private final AiProperties config;
  private final Map<EmbeddingProtocol, EmbeddingAdapter> adapters;

  /**
   * 创建向量模型客户端。
   *
   * @param config AI 模型配置
   * @param adapters 可用协议适配器
   */
  public EmbeddingClient(AiProperties config, List<EmbeddingAdapter> adapters) {
    this.config = config;
    this.adapters = new EnumMap<>(EmbeddingProtocol.class);
    for (EmbeddingAdapter adapter : adapters) {
      if (this.adapters.putIfAbsent(adapter.protocol(), adapter) != null)
        throw new IllegalArgumentException("Duplicate embedding adapter: " + adapter.protocol());
    }
    if (!config.embeddingModels().isEmpty()) {
      adapter();
    }
  }

  /**
   * 返回默认向量模型名称。
   *
   * @return 模型名称
   */
  public String model() {
    return config.embeddingModel().model();
  }

  /**
   * 返回默认向量维度。
   *
   * @return 向量维度
   */
  public int dimensions() {
    return config.embeddingModel().dimension();
  }

  /** 校验默认向量模型是否已完整配置。 */
  public void requireConfigured() {
    AiProperties.ModelTarget target = config.embeddingModel();
    adapter().requireConfigured(target);
  }

  /**
   * 校验指定的知识库向量模型是否仍可使用。
   *
   * @param modelId 模型配置标识
   * @param provider 向量供应商
   * @param model 模型名称
   * @param dimensions 向量维度
   */
  public void requireConfigured(String modelId, String provider, String model, int dimensions) {
    AiProperties.ModelTarget target = target(modelId, provider, model, dimensions);
    adapter().requireConfigured(target);
  }

  /**
   * 使用默认模型批量生成向量。
   *
   * @param texts 待编码文本
   * @return 与输入顺序一致的向量列表
   */
  public List<float[]> embed(List<String> texts) {
    return embedTarget(config.embeddingModel(), texts);
  }

  /**
   * 使用指定模型批量生成向量。
   *
   * @param modelId 模型配置标识
   * @param provider 向量供应商
   * @param model 模型名称
   * @param dimensions 向量维度
   * @param texts 待编码文本
   * @return 与输入顺序一致的向量列表
   */
  public List<float[]> embed(
      String modelId, String provider, String model, int dimensions, List<String> texts) {
    return embedTarget(target(modelId, provider, model, dimensions), texts);
  }

  /**
   * 将持久化的模型绑定解析为当前配置中的模型目标。
   *
   * @param modelId 模型配置标识
   * @param provider 向量供应商
   * @param model 模型名称
   * @param dimensions 向量维度
   * @return 匹配的模型目标
   */
  private AiProperties.ModelTarget target(
      String modelId, String provider, String model, int dimensions) {
    AiProperties.ModelTarget target;
    try {
      target = config.embeddingModel(modelId);
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.EMBEDDING_MODEL_UNAVAILABLE, "知识库绑定的向量模型已不在配置文件中");
    }
    if (!target.provider().equals(provider)
        || !target.model().equals(model)
        || target.dimension() != dimensions) {
      throw new ApiException(ErrorCode.EMBEDDING_BINDING_CHANGED, "向量模型配置已变更，请恢复原供应商、模型和维度");
    }
    return target;
  }

  /**
   * 按配置的批大小调用向量接口。
   *
   * @param target 模型目标
   * @param texts 待编码文本
   * @return 与输入顺序一致的向量列表
   */
  private List<float[]> embedTarget(AiProperties.ModelTarget target, List<String> texts) {
    EmbeddingAdapter adapter = adapter();
    adapter.requireConfigured(target);
    List<float[]> result = new ArrayList<>();
    int batchSize = config.getEmbedding().getBatchSize();
    // 分批请求可控制单次载荷大小，同时保持各批结果的整体输入顺序。
    for (int start = 0; start < texts.size(); start += batchSize) {
      List<String> batch = texts.subList(start, Math.min(texts.size(), start + batchSize));
      result.addAll(adapter.embed(target, batch));
    }
    return result;
  }

  private EmbeddingAdapter adapter() {
    EmbeddingAdapter adapter = adapters.get(EmbeddingProtocol.OPENAI_COMPATIBLE);
    if (adapter == null)
      throw new IllegalArgumentException("OpenAI-compatible embedding adapter is unavailable");
    return adapter;
  }

  /**
   * 将向量转换为 PostgreSQL pgvector 可接受的字面量。
   *
   * @param vector 向量值
   * @return pgvector 文本表示
   */
  public static String literal(float[] vector) {
    return Arrays.toString(vector);
  }
}
