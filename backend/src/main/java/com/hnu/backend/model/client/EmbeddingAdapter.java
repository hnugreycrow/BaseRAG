package com.hnu.backend.model.client;

import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.model.config.EmbeddingProtocol;
import java.util.List;

/** 一个 Embedding 协议的请求、响应和配置校验边界。 */
public interface EmbeddingAdapter {
  EmbeddingProtocol protocol();

  void requireConfigured(AiProperties.ModelTarget target);

  List<float[]> embed(AiProperties.ModelTarget target, List<String> texts);
}
