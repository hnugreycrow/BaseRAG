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
  private int chunkSize = 1400;
  private int chunkMinSize = 500;
  private int chunkMaxSize = 2000;
  private int chunkOverlap = 180;
  private int topK = 5;
  private int maxQuestionChars = 2000;

  @PostConstruct
  void validate() {
    if (chunkSize < 16
        || chunkMinSize < 1
        || chunkMinSize > chunkSize
        || chunkMaxSize < chunkSize
        || chunkOverlap < 0
        || chunkOverlap >= chunkSize
        || topK < 1
        || topK > 50
        || maxQuestionChars < 1
        || pipeline.maxSubQuestions < 1
        || pipeline.maxSubQuestions > 16
        || !Double.isFinite(pipeline.routing.confidenceThreshold)
        || pipeline.routing.confidenceThreshold < 0
        || pipeline.routing.confidenceThreshold > 1
        || pipeline.routing.timeoutMs < 1
        || pipeline.mcp.timeoutMs < 1
        || pipeline.mcp.maxOutputChars < 1
        || pipeline.mcp.allowList.stream().anyMatch(name -> name == null || name.isBlank())) {
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
  public static class Storage {
    private String endpoint;
    private String accessKey = "";
    private String secretKey = "";
    private String bucket = "jagent";
    private String region = "us-east-1";
  }
}
