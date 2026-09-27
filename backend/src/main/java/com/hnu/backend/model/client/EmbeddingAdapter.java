package com.hnu.backend.model.client;

import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.model.config.EmbeddingProtocol;
import java.util.List;

/** 一个 Embedding 协议的请求、响应和配置校验边界。 */
public interface EmbeddingAdapter {
  /**
   * 标识当前适配器处理的协议。
   *
   * @return 与配置候选匹配的 Embedding 协议
   */
  EmbeddingProtocol protocol();

  /**
   * 在发送请求前校验候选所需的地址和凭据。
   *
   * @param target 待调用的模型候选
   * @throws com.hnu.backend.common.exception.ApiException 候选配置不完整时抛出
   */
  void requireConfigured(AiProperties.ModelTarget target);

  /**
   * 批量生成向量；返回顺序须与输入文本一致。
   *
   * @param target 已配置的模型候选
   * @param texts 按请求顺序排列的非空文本列表
   * @return 每个输入文本对应的向量
   */
  List<float[]> embed(AiProperties.ModelTarget target, List<String> texts);
}
