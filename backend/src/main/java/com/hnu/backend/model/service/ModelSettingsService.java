package com.hnu.backend.model.service;

import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.model.vo.ModelSettingsResponse;
import java.util.List;
import org.springframework.stereotype.Service;

/** 读取实际生效的模型配置，并通过字段白名单移除敏感连接信息。 */
@Service
public class ModelSettingsService {
  private final AiProperties config;

  /**
   * 创建配置查询服务。
   *
   * @param config 当前进程的模型配置
   */
  public ModelSettingsService(AiProperties config) {
    this.config = config;
  }

  /**
   * 获取配置摘要，不调用模型服务或进行健康检测。
   *
   * @return 保持实际候选顺序的只读摘要
   */
  public ModelSettingsResponse get() {
    var chat = config.chatModels();
    var selection = config.getSelection();
    return new ModelSettingsResponse(
        config.getChat().getDefaultTier(),
        summarize(chat, chat.getFirst().id()),
        summarize(config.embeddingModels(), config.getEmbedding().getDefaultModel()),
        summarize(config.rerankModels(), config.getRerank().getDefaultModel()),
        selection.getMaxRetries(),
        selection.getFailureThreshold(),
        selection.getOpenDurationMs(),
        config.getEmbedding().getBatchSize());
  }

  private List<ModelSettingsResponse.Model> summarize(
      List<AiProperties.ModelTarget> targets, String defaultId) {
    return targets.stream()
        .map(
            target ->
                new ModelSettingsResponse.Model(
                    target.id(),
                    target.provider(),
                    target.model(),
                    target.id().equals(defaultId),
                    target.timeoutMs(),
                    target.dimension(),
                    target.supportsThinking(),
                    target.apiKey() != null && !target.apiKey().isBlank(),
                    "noop".equals(target.provider()) && "noop".equals(target.model())))
        .toList();
  }
}
