package com.hnu.backend.model.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AiProperties {
  private Map<String, Provider> providers = new LinkedHashMap<>();
  private Selection selection = new Selection();
  private Stream stream = new Stream();
  private Chat chat = new Chat();
  private Embedding embedding = new Embedding();
  private Rerank rerank = new Rerank();

  @PostConstruct
  void validate() {
    if (selection.failureThreshold < 1
        || selection.openDurationMs < 1
        || selection.maxRetries < 0
        || selection.maxRetries > 3
        || stream.messageChunkSize < 1
        || embedding.batchSize < 1
        || embedding.batchSize > 128
        || rerank.timeoutMs < 1) {
      throw new IllegalArgumentException("Invalid AI selection, stream or batch configuration");
    }
    chatModels();
    embeddingModels();
    embeddingModel();
    rerankModels();
  }

  public List<ModelTarget> chatModels() {
    Tier tier = chat.tiers.get(chat.defaultTier);
    if (tier == null || tier.candidates.isEmpty()) {
      throw new IllegalArgumentException(
          "AI chat default tier has no candidates: " + chat.defaultTier);
    }
    Map<String, Candidate> indexed = index(chat.candidates);
    return tier.candidates.stream()
        .map(id -> resolve(indexed.get(id), id, "chat", tier.timeoutMs, 0))
        .toList();
  }

  public ModelTarget embeddingModel() {
    return embeddingModel(embedding.defaultModel);
  }

  public ModelTarget embeddingModel(String id) {
    Candidate candidate = index(embedding.candidates).get(id);
    int dimension = candidate == null ? 0 : candidate.dimension;
    return resolve(candidate, id, "embedding", embedding.timeoutMs, dimension);
  }

  public ModelTarget embeddingModel(String model, int dimension) {
    return embedding.candidates.stream()
        .filter(candidate -> candidate.model.equals(model) && candidate.dimension == dimension)
        .findFirst()
        .map(candidate -> embeddingModel(candidate.id))
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Knowledge base embedding model is no longer configured: " + model));
  }

  public List<ModelTarget> embeddingModels() {
    return embedding.candidates.stream().map(candidate -> embeddingModel(candidate.id)).toList();
  }

  /**
   * 返回重排模型的实际尝试顺序：默认模型始终优先，其余候选按优先级和 ID 稳定排列。
   *
   * <p>noop 是本地确定性降级哨兵，不代表一次真实模型调用。
   */
  public List<ModelTarget> rerankModels() {
    Map<String, Candidate> indexed = index(rerank.candidates);
    Candidate primary = indexed.get(rerank.defaultModel);
    if (primary == null) {
      throw new IllegalArgumentException("Unknown rerank default model: " + rerank.defaultModel);
    }
    List<Candidate> ordered = new ArrayList<>();
    ordered.add(primary);
    rerank.candidates.stream()
        .filter(candidate -> !candidate.id.equals(rerank.defaultModel))
        .sorted(Comparator.comparingInt(Candidate::getPriority).thenComparing(Candidate::getId))
        .forEach(ordered::add);
    return ordered.stream().map(this::resolveRerank).toList();
  }

  private Map<String, Candidate> index(List<Candidate> candidates) {
    Map<String, Candidate> indexed = new LinkedHashMap<>();
    for (Candidate candidate : candidates) {
      if (candidate.id == null
          || candidate.id.isBlank()
          || indexed.put(candidate.id, candidate) != null) {
        throw new IllegalArgumentException("AI candidate id must be non-empty and unique");
      }
    }
    return indexed;
  }

  private ModelTarget resolve(
      Candidate candidate, String id, String capability, int timeoutMs, int dimension) {
    if (candidate == null
        || candidate.provider == null
        || candidate.model == null
        || candidate.model.isBlank()) {
      throw new IllegalArgumentException("Unknown or incomplete AI candidate: " + id);
    }
    Provider provider = providers.get(candidate.provider);
    if (provider == null)
      throw new IllegalArgumentException("Unknown AI provider: " + candidate.provider);
    String path =
        switch (capability) {
          case "chat" -> provider.endpoints.chat;
          case "embedding" -> provider.endpoints.embedding;
          case "rerank" -> provider.endpoints.rerank;
          default -> throw new IllegalArgumentException("Unsupported AI capability: " + capability);
        };
    if (provider.url == null
        || provider.url.isBlank()
        || path == null
        || !path.startsWith("/")
        || timeoutMs < 1) {
      throw new IllegalArgumentException(
          "Incomplete AI provider endpoint: " + candidate.provider + "/" + capability);
    }
    try {
      URI uri = URI.create(provider.url);
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid AI provider URL: " + candidate.provider);
    }
    if ("embedding".equals(capability) && (dimension < 1 || dimension > 16000)) {
      throw new IllegalArgumentException("Embedding dimension must be between 1 and 16000");
    }
    return new ModelTarget(
        candidate.id,
        candidate.provider,
        candidate.model,
        provider.url.replaceAll("/+$", ""),
        path,
        provider.apiKey,
        timeoutMs,
        dimension,
        candidate.supportsThinking);
  }

  private ModelTarget resolveRerank(Candidate candidate) {
    if (candidate == null) {
      throw new IllegalArgumentException("Unknown rerank candidate");
    }
    // noop 不访问网络，因此刻意不要求在 providers 下配置地址、端点或密钥。
    if ("noop".equals(candidate.provider) && "noop".equals(candidate.model)) {
      return new ModelTarget(candidate.id, "noop", "noop", "", "", "", rerank.timeoutMs, 0, false);
    }
    if ("noop".equals(candidate.provider) || "noop".equals(candidate.model)) {
      throw new IllegalArgumentException("Incomplete noop rerank candidate: " + candidate.id);
    }
    return resolve(candidate, candidate.id, "rerank", rerank.timeoutMs, 0);
  }

  public record ModelTarget(
      String id,
      String provider,
      String model,
      String baseUrl,
      String endpoint,
      String apiKey,
      int timeoutMs,
      int dimension,
      boolean supportsThinking) {}

  @Data
  public static class Provider {
    private String url = "";
    private String apiKey = "";
    private Endpoints endpoints = new Endpoints();
  }

  @Data
  public static class Endpoints {
    private String chat = "";
    private String embedding = "";
    private String rerank = "";
  }

  @Data
  public static class Selection {
    private int failureThreshold = 2;
    private long openDurationMs = 30_000;
    private int maxRetries = 2;
  }

  @Data
  public static class Stream {
    private int messageChunkSize = 1;
  }

  @Data
  public static class Chat {
    private String defaultTier = "standard";
    private List<Candidate> candidates = new ArrayList<>();
    private Map<String, Tier> tiers = new LinkedHashMap<>();
  }

  @Data
  public static class Tier {
    private List<String> candidates = new ArrayList<>();
    private int timeoutMs = 30_000;
  }

  @Data
  public static class Embedding {
    private String defaultModel = "";
    private List<Candidate> candidates = new ArrayList<>();
    private int batchSize = 16;
    private int timeoutMs = 60_000;
  }

  @Data
  public static class Rerank {
    private String defaultModel = "";
    private List<Candidate> candidates = new ArrayList<>();
    private int timeoutMs = 8_000;
  }

  @Data
  public static class Candidate {
    private String id = "";
    private String provider = "";
    private String model = "";
    private int dimension;
    private int priority;
    private boolean supportsThinking;
  }
}
