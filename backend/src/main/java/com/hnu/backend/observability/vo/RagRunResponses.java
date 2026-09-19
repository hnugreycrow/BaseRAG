package com.hnu.backend.observability.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hnu.backend.observability.RagExecutionMode;
import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.RagStageStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 单次问答观测接口使用的响应类型，仅暴露原始问题正文。 */
public final class RagRunResponses {
  /** 禁止实例化响应类型容器。 */
  private RagRunResponses() {}

  /**
   * 列表和详情共用的运行摘要。
   *
   * @param id 运行标识
   * @param ownerId 管理员响应中的所属用户标识；普通用户响应省略
   * @param username 管理员响应中的用户名；普通用户响应省略
   * @param displayName 管理员响应中的显示名；普通用户响应省略
   * @param requestId HTTP 请求标识
   * @param conversationId 可选会话标识
   * @param userMessageId 可选用户消息标识
   * @param assistantMessageId 可选回答版本标识
   * @param question 可选原始问题快照；无法回填的旧记录为空
   * @param status 运行状态
   * @param executionMode 执行模式
   * @param modelId 最终模型配置标识
   * @param provider 最终模型供应商
   * @param model 最终模型名称
   * @param candidateCount 候选数量
   * @param evidenceCount 最终证据数量
   * @param degraded 是否发生过降级
   * @param errorCode 可选错误码
   * @param startedAt 开始时间
   * @param firstTokenAt 首个成功发送思考或正文增量的时间
   * @param completedAt 完成时间
   * @param totalMs 总耗时
   * @param endToEndTtftMs 端到端首 Token 耗时
   * @param modelTtftMs 最终有效回答模型首内容耗时
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Summary(
      UUID id,
      UUID ownerId,
      String username,
      String displayName,
      String requestId,
      UUID conversationId,
      UUID userMessageId,
      UUID assistantMessageId,
      String question,
      RagRunStatus status,
      RagExecutionMode executionMode,
      String modelId,
      String provider,
      String model,
      int candidateCount,
      int evidenceCount,
      boolean degraded,
      String errorCode,
      OffsetDateTime startedAt,
      OffsetDateTime firstTokenAt,
      OffsetDateTime completedAt,
      Long totalMs,
      Long endToEndTtftMs,
      Long modelTtftMs) {}

  /**
   * 单个阶段的瀑布信息。
   *
   * @param id 阶段标识
   * @param stageName 阶段名
   * @param subQuestionId 可选子问题标识
   * @param sequenceNo 开始顺序
   * @param status 阶段状态
   * @param inputCount 输入项数量
   * @param outputCount 输出项数量
   * @param modelId 模型配置标识
   * @param provider 模型供应商
   * @param model 模型名称
   * @param reasonCode 状态原因
   * @param errorCode 错误码
   * @param startedAt 开始时间
   * @param firstTokenAt 首内容时间
   * @param completedAt 完成时间
   * @param elapsedMs 阶段耗时
   * @param ttftMs 阶段首内容耗时
   */
  public record Stage(
      UUID id,
      RagStageName stageName,
      String subQuestionId,
      int sequenceNo,
      RagStageStatus status,
      Integer inputCount,
      Integer outputCount,
      String modelId,
      String provider,
      String model,
      String reasonCode,
      String errorCode,
      OffsetDateTime startedAt,
      OffsetDateTime firstTokenAt,
      OffsetDateTime completedAt,
      long elapsedMs,
      Long ttftMs) {}

  /**
   * 单次运行详情。
   *
   * @param run 运行摘要
   * @param stages 阶段瀑布
   * @param degradationReasons 去重且保持首次出现顺序的降级原因
   */
  public record Detail(Summary run, List<Stage> stages, List<String> degradationReasons) {}

  /**
   * 一项延迟的 P50/P95。
   *
   * @param p50Ms 中位数毫秒
   * @param p95Ms 第 95 百分位毫秒
   */
  public record Percentiles(Long p50Ms, Long p95Ms) {}

  /**
   * 过滤范围内的汇总指标。
   *
   * @param requestCount 请求数量
   * @param successRate 终态运行成功率，范围 0 到 1
   * @param degradedRate 终态运行降级率，范围 0 到 1
   * @param totalMs 总耗时分位数
   * @param endToEndTtftMs 端到端 TTFT 分位数
   * @param modelTtftMs 模型 TTFT 分位数
   */
  public record Aggregate(
      long requestCount,
      double successRate,
      double degradedRate,
      Percentiles totalMs,
      Percentiles endToEndTtftMs,
      Percentiles modelTtftMs) {}
}
