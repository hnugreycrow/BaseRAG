package com.hnu.backend.model.client;

import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.model.config.EmbeddingProtocol;
import com.hnu.backend.model.http.ModelHttpClient;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 处理兼容 OpenAI 的 Embedding 请求和响应，与具体供应商无关。 */
@Component
public class OpenAICompatibleEmbeddingAdapter implements EmbeddingAdapter {
  private final ModelHttpClient http;

  public OpenAICompatibleEmbeddingAdapter(ModelHttpClient http) {
    this.http = http;
  }

  @Override
  public EmbeddingProtocol protocol() {
    return EmbeddingProtocol.OPENAI_COMPATIBLE;
  }

  @Override
  public void requireConfigured(AiProperties.ModelTarget target) {
    http.requireConfigured(target);
  }

  @Override
  public List<float[]> embed(AiProperties.ModelTarget target, List<String> texts) {
    JsonNode response =
        http.post(
            target,
            Map.of(
                "model",
                target.model(),
                "input",
                texts,
                "encoding_format",
                "float",
                "dimensions",
                target.dimension()));
    return parse(response, texts.size(), target.dimension());
  }

  /** 严格按 index 恢复原输入顺序，并校验数量、维度与向量值。 */
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
        vector[j] = (float) values.get(j).doubleValue();
        if (!Float.isFinite(vector[j])) throw invalid();
        nonZero |= vector[j] != 0;
      }
      if (!nonZero) throw invalid();
      ordered[i] = vector;
    }
    return Arrays.asList(ordered);
  }

  private static ApiException invalid() {
    return ApiException.upstream(
        ErrorCode.EMBEDDING_INVALID_RESPONSE, "Embedding 返回的数量、索引、维度或向量值无效");
  }
}
