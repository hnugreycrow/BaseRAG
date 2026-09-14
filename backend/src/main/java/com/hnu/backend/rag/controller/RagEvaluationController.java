package com.hnu.backend.rag.controller;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.rag.vo.RagEvaluationConfigResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供本地评测运行所需的非敏感 RAG 配置快照。 */
@RestController
@Profile("local")
@RequestMapping("/api/evaluation")
public class RagEvaluationController {
  private final RagProperties rag;

  public RagEvaluationController(RagProperties rag) {
    this.rag = rag;
  }

  /** 返回影响分块和检索结果的当前生效参数，不包含任何凭据。 */
  @GetMapping("/config")
  public RagEvaluationConfigResponse config() {
    return new RagEvaluationConfigResponse(
        rag.getChunkSize(),
        rag.getChunkMinSize(),
        rag.getChunkMaxSize(),
        rag.getChunkOverlap(),
        rag.getTopK(),
        rag.getMaxQuestionChars());
  }
}
