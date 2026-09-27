package com.hnu.backend.rag.service;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.rag.api.RagEngine;
import com.hnu.backend.rag.api.RagObserver;
import com.hnu.backend.rag.api.RagRequest;
import com.hnu.backend.rag.config.RagProperties;
import com.hnu.backend.rag.vo.AnswerResponse;
import com.hnu.backend.rag.vo.ModelInfoResponse;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 编排单轮 RAG 问答流程，包括检索、上下文构造、模型生成和引用校验。 */
@Service
public class RagService {
  private static final Logger log = LoggerFactory.getLogger(RagService.class);
  private final RagEngine engine;
  private final RagProperties config;

  /** 创建旧单轮问答入口；流程由统一引擎的兼容模式执行。 */
  public RagService(RagEngine engine, RagProperties config) {
    this.engine = engine;
    this.config = config;
  }

  /**
   * 根据问题检索全部可用知识库并生成回答。
   *
   * <p>模型首次返回非法引用时会使用相同证据修复一次，仍不合法则向上游报告错误。
   *
   * @param ownerId 所属用户标识
   * @param question 用户问题
   * @return 回答、来源、实际引用和模型信息
   */
  public AnswerResponse ask(UUID ownerId, String question) {
    return ask(ownerId, question, null);
  }

  /**
   * 根据问题和可选知识库范围生成回答。
   *
   * @param ownerId 提问者标识；外部知识库范围不能扩大公共检索范围
   * @param question 用户问题
   * @param knowledgeBaseIds 允许检索的知识库；null 表示全部知识库
   * @return 回答、来源、实际引用和模型信息
   */
  public AnswerResponse ask(UUID ownerId, String question, List<UUID> knowledgeBaseIds) {
    if (question == null
        || question.isBlank()
        || question.length() > config.getMaxQuestionChars()) {
      throw ApiException.bad(
          ErrorCode.INVALID_QUESTION, "请输入非空问题，长度不能超过 " + config.getMaxQuestionChars() + " 字符");
    }
    try (var control = engine.newControl()) {
      var result =
          engine.execute(
              new RagRequest(
                  ownerId,
                  question.strip(),
                  null,
                  0,
                  knowledgeBaseIds,
                  false,
                  RagRequest.Mode.LEGACY),
              RagObserver.noop(),
              control,
              RagRunTrace.noop());
      var generation = result.generation();
      return new AnswerResponse(
          result.content(),
          result.sources(),
          result.citations(),
          generation == null
              ? null
              : new ModelInfoResponse(
                  generation.modelId(), generation.provider(), generation.model()));
    }
  }
}
