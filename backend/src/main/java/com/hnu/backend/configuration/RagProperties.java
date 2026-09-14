package com.hnu.backend.configuration;

import jakarta.annotation.PostConstruct;
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
        || pipeline.maxSubQuestions > 16) {
      throw new IllegalArgumentException("Invalid RAG size configuration");
    }
  }

  @Data
  public static class Pipeline {
    private int maxSubQuestions = 4;
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
