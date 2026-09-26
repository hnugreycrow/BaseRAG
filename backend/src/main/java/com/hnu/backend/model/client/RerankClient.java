package com.hnu.backend.model.client;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import com.hnu.backend.model.config.AiProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 调用配置的文本重排模型，并在供应商失败时按候选顺序切换。 */
@Component
public class RerankClient {
  private static final Logger log = LoggerFactory.getLogger(RerankClient.class);
  private static final String QA_INSTRUCTION =
      "Given a web search query, retrieve relevant passages that answer the query.";

  private final AiProperties config;
  private final ModelHttpClient http;

  public RerankClient(AiProperties config, ModelHttpClient http) {
    this.config = config;
    this.http = http;
  }

  public Generation rerank(String query, List<String> documents) {
    if (query == null || query.isBlank() || documents.isEmpty()) {
      throw new IllegalArgumentException("Rerank query and documents must not be empty");
    }
    ApiException last = null;
    for (AiProperties.ModelTarget target : config.rerankModels()) {
      if (Thread.currentThread().isInterrupted()) {
        throw ApiException.cancelled();
      }
      if ("noop".equals(target.provider())) {
        // noop 只表达“使用确定性融合排序”，不能伪造模型相关性分数。
        return Generation.noop(target.id());
      }
      try {
        return http.post(
            target,
            payload(target, query, documents),
            response -> parse(target, response, documents.size()));
      } catch (ApiException error) {
        if (ErrorCode.REQUEST_INTERRUPTED.code().equals(error.code())
            || ErrorCode.GENERATION_CANCELLED.code().equals(error.code())) {
          throw error;
        }
        log.warn(
            "rerank model failed modelId={} provider={} code={}",
            target.id(),
            target.provider(),
            error.code());
        last = error;
      } catch (RuntimeException error) {
        log.warn(
            "rerank model failed modelId={} provider={} code={}",
            target.id(),
            target.provider(),
            ErrorCode.RERANK_INVALID_RESPONSE.code());
        last = ApiException.upstream(ErrorCode.RERANK_INVALID_RESPONSE, "重排模型返回了无效结果", error);
      }
    }
    throw last == null ? ApiException.upstream(ErrorCode.RERANK_UNAVAILABLE, "没有可用的重排模型") : last;
  }

  private Map<String, Object> payload(
      AiProperties.ModelTarget target, String query, List<String> documents) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", target.model());
    payload.put("query", query);
    payload.put("documents", List.copyOf(documents));
    payload.put("top_n", documents.size());
    payload.put("instruct", QA_INSTRUCTION);
    return payload;
  }

  private Generation parse(AiProperties.ModelTarget target, JsonNode response, int documentCount) {
    JsonNode results = response.path("results");
    if (!results.isArray() || results.size() != documentCount) {
      throw invalid();
    }
    List<Rank> ranks = new ArrayList<>();
    Set<Integer> seen = new HashSet<>();
    for (JsonNode item : results) {
      JsonNode indexNode = item.path("index");
      JsonNode scoreNode = item.path("relevance_score");
      if (!item.isObject() || !indexNode.isIntegralNumber() || !scoreNode.isNumber()) {
        throw invalid();
      }
      int index = indexNode.asInt();
      double score = scoreNode.asDouble();
      if (index < 0
          || index >= documentCount
          || !seen.add(index)
          || !Double.isFinite(score)
          || score < 0
          || score > 1) {
        throw invalid();
      }
      ranks.add(new Rank(index, score));
    }
    if (seen.size() != documentCount) {
      throw invalid();
    }
    String requestId = response.path("id").isString() ? response.path("id").asString() : null;
    String model =
        response.path("model").isString() ? response.path("model").asString() : target.model();
    long totalTokens =
        response.path("usage").path("total_tokens").isIntegralNumber()
            ? response.path("usage").path("total_tokens").asLong()
            : 0;
    return new Generation(
        ranks, target.id(), target.provider(), model, requestId, totalTokens, false);
  }

  private ApiException invalid() {
    return ApiException.upstream(ErrorCode.RERANK_INVALID_RESPONSE, "重排模型返回了无效结果");
  }

  /**
   * 一次重排模型调用的中立结果。
   *
   * @param ranks 输入文档下标及其单次请求内可比较的相关性分数
   * @param modelId 本地配置中的模型候选 ID
   * @param provider 模型供应商标识
   * @param model 供应商实际返回或配置的模型名称
   * @param requestId 供应商请求 ID，noop 或供应商未返回时为空
   * @param totalTokens 供应商报告的本次输入 Token 数，未返回时为零
   * @param noop 是否命中不访问网络的确定性降级候选
   */
  public record Generation(
      List<Rank> ranks,
      String modelId,
      String provider,
      String model,
      String requestId,
      long totalTokens,
      boolean noop) {
    public Generation {
      ranks = List.copyOf(ranks);
    }

    private static Generation noop(String modelId) {
      return new Generation(List.of(), modelId, "noop", "noop", null, 0, true);
    }
  }

  /**
   * 模型为一个输入文档返回的相关性结果。
   *
   * @param index 文档在请求 documents 数组中的零基下标
   * @param relevanceScore 仅在本次请求内可比较的相关性分数，范围为 0 到 1
   */
  public record Rank(int index, double relevanceScore) {}
}
