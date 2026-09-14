package com.hnu.backend.model.client;

import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.model.http.ModelHttpClient;
import com.hnu.backend.shared.error.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 调用兼容 OpenAI 协议的向量模型，并严格校验返回向量。 */
@Component
public class EmbeddingClient {
  private final AiProperties config;
  private final ModelHttpClient http;

  /**
   * 创建向量模型客户端。
   *
   * @param config AI 模型配置
   * @param http 模型 HTTP 客户端
   */
  public EmbeddingClient(AiProperties config, ModelHttpClient http) {
    this.config = config;
    this.http = http;
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
    http.requireConfigured(config.embeddingModel());
  }

  /**
   * 校验指定的知识库向量模型是否仍可使用。
   *
   * @param model 模型名称
   * @param dimensions 向量维度
   */
  public void requireConfigured(String model, int dimensions) {
    http.requireConfigured(target(model, dimensions));
  }

  /**
   * 使用默认模型批量生成向量。
   *
   * @param texts 待编码文本
   * @return 与输入顺序一致的向量列表
   */
  public List<float[]> embed(List<String> texts) {
    return embed(config.embeddingModel(), texts);
  }

  /**
   * 使用指定模型批量生成向量。
   *
   * @param model 模型名称
   * @param dimensions 向量维度
   * @param texts 待编码文本
   * @return 与输入顺序一致的向量列表
   */
  public List<float[]> embed(String model, int dimensions, List<String> texts) {
    return embed(target(model, dimensions), texts);
  }

  /**
   * 将持久化的模型绑定解析为当前配置中的模型目标。
   *
   * @param model 模型名称
   * @param dimensions 向量维度
   * @return 匹配的模型目标
   */
  private AiProperties.ModelTarget target(String model, int dimensions) {
    try {
      return config.embeddingModel(model, dimensions);
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          "EMBEDDING_MODEL_UNAVAILABLE", "知识库绑定的向量模型已不在配置文件中", HttpStatus.CONFLICT);
    }
  }

  /**
   * 按配置的批大小调用向量接口。
   *
   * @param target 模型目标
   * @param texts 待编码文本
   * @return 与输入顺序一致的向量列表
   */
  private List<float[]> embed(AiProperties.ModelTarget target, List<String> texts) {
    http.requireConfigured(target);
    List<float[]> result = new ArrayList<>();
    int batchSize = config.getEmbedding().getBatchSize();
    // 分批请求可控制单次载荷大小，同时保持各批结果的整体输入顺序。
    for (int start = 0; start < texts.size(); start += batchSize) {
      List<String> batch = texts.subList(start, Math.min(texts.size(), start + batchSize));
      JsonNode response =
          http.post(
              target,
              Map.of(
                  "model",
                  target.model(),
                  "input",
                  batch,
                  "encoding_format",
                  "float",
                  "dimensions",
                  target.dimension()));
      result.addAll(parse(response, batch.size(), target.dimension()));
    }
    return result;
  }

  /**
   * 解析并校验模型响应，按响应中的 index 恢复输入顺序。
   *
   * @param response 模型原始 JSON 响应
   * @param count 期望向量数量
   * @param dimensions 期望向量维度
   * @return 已校验且按输入顺序排列的向量
   */
  static List<float[]> parse(JsonNode response, int count, int dimensions) {
    JsonNode data = response.path("data");
    if (!data.isArray() || data.size() != count) throw invalid();
    // 服务端不保证 data 数组顺序，必须使用 index 放回对应输入位置。
    float[][] ordered = new float[count][];
    for (JsonNode row : data) {
      JsonNode index = row.path("index");
      if (!index.isIntegralNumber() || !index.canConvertToInt()) throw invalid();
      int i = index.intValue();
      if (i < 0 || i >= count || ordered[i] != null) throw invalid();
      JsonNode values = row.path("embedding");
      if (!values.isArray() || values.size() != dimensions) throw invalid();
      float[] vector = new float[dimensions];
      boolean nonZero = false;
      for (int j = 0; j < dimensions; j++) {
        if (!values.get(j).isNumber()) throw invalid();
        try {
          vector[j] = (float) values.get(j).doubleValue();
        } catch (RuntimeException e) {
          throw invalid();
        }
        if (!Float.isFinite(vector[j])) throw invalid();
        nonZero |= vector[j] != 0;
      }
      if (!nonZero) throw invalid();
      ordered[i] = vector;
    }
    return Arrays.asList(ordered);
  }

  /**
   * 创建统一的向量响应校验异常。
   *
   * @return 上游响应无效异常
   */
  private static ApiException invalid() {
    return ApiException.upstream("EMBEDDING_INVALID_RESPONSE", "Embedding 返回的数量、索引、维度或向量值无效");
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
