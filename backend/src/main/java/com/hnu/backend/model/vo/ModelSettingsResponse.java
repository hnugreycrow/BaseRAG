package com.hnu.backend.model.vo;

import java.util.List;

/**
 * 当前进程使用的模型配置快照，不包含密钥或连接地址。
 *
 * @param chatTier 当前对话档位
 * @param chat 对话模型，按实际尝试顺序排列
 * @param embedding 可选向量模型，保持配置顺序
 * @param rerank 重排模型，按实际尝试顺序排列
 * @param maxRetries 单个模型可重试错误的最大重试次数
 * @param failureThreshold 熔断失败次数阈值
 * @param openDurationMs 熔断持续时间，单位毫秒
 * @param embeddingBatchSize 向量化批次大小
 */
public record ModelSettingsResponse(
    String chatTier,
    List<Model> chat,
    List<Model> embedding,
    List<Model> rerank,
    int maxRetries,
    int failureThreshold,
    long openDurationMs,
    int embeddingBatchSize) {
  /**
   * 模型的公开配置摘要；凭据存在不代表服务可用。
   *
   * @param id 配置标识
   * @param provider 服务商标识
   * @param model 模型名称
   * @param defaultModel 是否为当前用途的默认或首选模型
   * @param timeoutMs 请求超时，单位毫秒；本地跳过重排时不适用
   * @param dimensions 向量维度，非向量模型为零
   * @param supportsThinking 是否声明支持思考能力
   * @param credentialConfigured 是否配置非空凭据，本地跳过重排时为 false
   * @param localFallback 是否为不请求远端服务的跳过重排策略
   */
  public record Model(
      String id,
      String provider,
      String model,
      boolean defaultModel,
      int timeoutMs,
      int dimensions,
      boolean supportsThinking,
      boolean credentialConfigured,
      boolean localFallback) {}
}
