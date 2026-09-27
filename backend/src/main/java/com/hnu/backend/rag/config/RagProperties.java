package com.hnu.backend.rag.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** RAG 流水线的可配置预算和检索策略，启动时统一验证边界条件。 */
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
        || pipeline.planning.recentTurns < 1
        || pipeline.planning.recentTurns > 8
        || !Double.isFinite(pipeline.routing.confidenceThreshold)
        || pipeline.routing.confidenceThreshold < 0
        || pipeline.routing.confidenceThreshold > 1
        || pipeline.routing.timeoutMs < 1
        || pipeline.mcp.timeoutMs < 1
        || pipeline.mcp.maxOutputChars < 1
        || pipeline.mcp.allowList.stream().anyMatch(name -> name == null || name.isBlank())
        || !Double.isFinite(pipeline.deduplication.overlapThreshold)
        || pipeline.deduplication.overlapThreshold <= 0
        || pipeline.deduplication.overlapThreshold > 1
        || pipeline.rerank.maxInputCandidates < 1
        || pipeline.rerank.maxInputCandidates > 500
        || pipeline.rerank.selectedEvidence < 1
        || pipeline.rerank.selectedEvidence < pipeline.maxSubQuestions
        || pipeline.rerank.selectedEvidence > pipeline.rerank.maxInputCandidates
        || search.defaultTopK < 1
        || search.defaultTopK > 50
        || search.recallBudget < 0
        || (search.recallBudget > 0 && search.recallBudget < search.defaultTopK)
        || search.channels.timeoutMs < 1
        || !"rrf".equalsIgnoreCase(search.fusion.strategy)
        || search.fusion.rrfK < 1
        || !Double.isFinite(search.fusion.channelWeights.vector)
        || search.fusion.channelWeights.vector <= 0) {
      throw new IllegalArgumentException("Invalid RAG configuration");
    }
  }

  /** 子问题数量及规划、路由、工具、去重和重排阶段的配置集合。 */
  @Data
  public static class Pipeline {
    private int maxSubQuestions = 4;
    private Planning planning = new Planning();
    private Routing routing = new Routing();
    private Mcp mcp = new Mcp();
    private Deduplication deduplication = new Deduplication();
    private Rerank rerank = new Rerank();
  }

  /** 查询规划可读取的最近完整会话轮次数量。 */
  @Data
  public static class Planning {
    private int recentTurns = 4;
  }

  /** 意图路由的模型置信度阈值和调用超时，阈值范围为 0 到 1。 */
  @Data
  public static class Routing {
    private double confidenceThreshold = 0.70;
    private int timeoutMs = 10_000;
  }

  /** MCP 工具开关、白名单及单次执行的时间和输出长度预算。 */
  @Data
  public static class Mcp {
    private boolean enabled;
    private List<String> allowList = new ArrayList<>();
    private int timeoutMs = 3000;
    private int maxOutputChars = 6000;
  }

  /** 相邻证据正文的重叠率去重阈值，范围为大于 0 且不超过 1。 */
  @Data
  public static class Deduplication {
    private double overlapThreshold = 0.85;
  }

  /** 重排的输入候选上限与最终证据数量预算。 */
  @Data
  public static class Rerank {
    private boolean enabled = true;
    private int maxInputCandidates = 40;
    private int selectedEvidence = 8;
  }

  /** 向量召回数量、通道超时和融合参数。 */
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

  /** 检索通道共享超时及各通道开关。 */
  @Data
  public static class Channels {
    private int timeoutMs = 5_000;
    private Vector vector = new Vector();
  }

  /** 向量检索通道的启用状态。 */
  @Data
  public static class Vector {
    private boolean enabled = true;
  }

  /** RRF 融合策略及通道权重。 */
  @Data
  public static class Fusion {
    private String strategy = "rrf";
    private int rrfK = 20;
    private ChannelWeights channelWeights = new ChannelWeights();
  }

  /** 向量通道参与融合时的正权重。 */
  @Data
  public static class ChannelWeights {
    private double vector = 1.0;
  }

  /** 原文件对象存储的连接与桶配置，凭据只供服务端使用。 */
  @Data
  public static class Storage {
    private String endpoint;
    private String accessKey = "";
    private String secretKey = "";
    private String bucket = "baserag";
    private String region = "us-east-1";
  }
}
