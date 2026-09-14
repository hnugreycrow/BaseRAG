package com.hnu.backend.configuration;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
  private Storage storage = new Storage();
  private Pipeline pipeline = new Pipeline();
  private Search search = new Search();
  private int chunkSize = 1400;
  private int chunkMinSize = 500;
  private int chunkMaxSize = 2000;
  private int chunkOverlap = 180;
  private int maxQuestionChars = 2000;

  @PostConstruct
  void validate() {
    if (chunkSize < 16
        || chunkMinSize < 1
        || chunkMinSize > chunkSize
        || chunkMaxSize < chunkSize
        || chunkOverlap < 0
        || chunkOverlap >= chunkSize
        || maxQuestionChars < 1
        || pipeline.maxSubQuestions < 1
        || pipeline.maxSubQuestions > 16
        || !Double.isFinite(pipeline.routing.confidenceThreshold)
        || pipeline.routing.confidenceThreshold < 0
        || pipeline.routing.confidenceThreshold > 1
        || pipeline.routing.timeoutMs < 1
        || pipeline.mcp.timeoutMs < 1
        || pipeline.mcp.maxOutputChars < 1
        || pipeline.mcp.allowList.stream().anyMatch(name -> name == null || name.isBlank())
        || search.defaultTopK < 1
        || search.defaultTopK > 50
        || search.recallBudget < 0
        || (search.recallBudget > 0 && search.recallBudget < search.defaultTopK)
        || search.channels.timeoutMs < 1
        || !"rrf".equalsIgnoreCase(search.fusion.strategy)
        || search.fusion.rrfK < 1
        || (search.fusion.rerankCandidateLimit > 0
            && search.fusion.rerankCandidateLimit < search.defaultTopK)
        || !Double.isFinite(search.fusion.channelWeights.vector)
        || search.fusion.channelWeights.vector <= 0) {
      throw new IllegalArgumentException("Invalid RAG configuration");
    }
  }

  @Data
  public static class Pipeline {
    private int maxSubQuestions = 4;
    private Routing routing = new Routing();
    private Mcp mcp = new Mcp();
  }

  @Data
  public static class Routing {
    private double confidenceThreshold = 0.70;
    private int timeoutMs = 5000;
  }

  @Data
  public static class Mcp {
    private boolean enabled;
    private List<String> allowList = new ArrayList<>();
    private int timeoutMs = 3000;
    private int maxOutputChars = 6000;
  }

  @Data
  public static class Search {
    private int defaultTopK = 10;
    private int recallBudget = 20;
    private Channels channels = new Channels();
    private Fusion fusion = new Fusion();

    /** recall-budget=0 是兼容简写，执行时按最终 Top K 解析为实际召回预算。 */
    public int effectiveRecallBudget() {
      return recallBudget == 0 ? defaultTopK : recallBudget;
    }
  }

  @Data
  public static class Channels {
    private int timeoutMs = 15_000;
    private Vector vector = new Vector();
  }

  @Data
  public static class Vector {
    private boolean enabled = true;
  }

  @Data
  public static class Fusion {
    private String strategy = "rrf";
    private int rrfK = 20;
    private int rerankCandidateLimit = 40;
    private ChannelWeights channelWeights = new ChannelWeights();
  }

  @Data
  public static class ChannelWeights {
    private double vector = 1.0;
  }

  @Data
  public static class Storage {
    private String endpoint;
    private String accessKey = "";
    private String secretKey = "";
    private String bucket = "jagent";
    private String region = "us-east-1";
  }
}
