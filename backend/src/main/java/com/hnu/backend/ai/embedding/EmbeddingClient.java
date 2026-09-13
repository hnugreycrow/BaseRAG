package com.hnu.backend.ai.embedding;

import com.hnu.backend.ai.config.AiProperties;
import com.hnu.backend.ai.infrastructure.http.ModelHttpClient;
import com.hnu.backend.shared.error.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class EmbeddingClient {
  private final AiProperties config;
  private final ModelHttpClient http;

  public EmbeddingClient(AiProperties config, ModelHttpClient http) {
    this.config = config;
    this.http = http;
  }

  public String model() {
    return config.embeddingModel().model();
  }

  public int dimensions() {
    return config.embeddingModel().dimension();
  }

  public void requireConfigured() {
    http.requireConfigured(config.embeddingModel());
  }

  public void requireConfigured(String model, int dimensions) {
    http.requireConfigured(target(model, dimensions));
  }

  public List<float[]> embed(List<String> texts) {
    return embed(config.embeddingModel(), texts);
  }

  public List<float[]> embed(String model, int dimensions, List<String> texts) {
    return embed(target(model, dimensions), texts);
  }

  private AiProperties.ModelTarget target(String model, int dimensions) {
    try {
      return config.embeddingModel(model, dimensions);
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          "EMBEDDING_MODEL_UNAVAILABLE", "知识库绑定的向量模型已不在配置文件中", HttpStatus.CONFLICT);
    }
  }

  private List<float[]> embed(AiProperties.ModelTarget target, List<String> texts) {
    http.requireConfigured(target);
    List<float[]> result = new ArrayList<>();
    int batchSize = config.getEmbedding().getBatchSize();
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

  static List<float[]> parse(JsonNode response, int count, int dimensions) {
    JsonNode data = response.path("data");
    if (!data.isArray() || data.size() != count) throw invalid();
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

  private static ApiException invalid() {
    return ApiException.upstream("EMBEDDING_INVALID_RESPONSE", "Embedding 返回的数量、索引、维度或向量值无效");
  }

  public static String literal(float[] vector) {
    return Arrays.toString(vector);
  }
}
