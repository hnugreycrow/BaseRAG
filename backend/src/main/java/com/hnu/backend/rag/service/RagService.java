package com.hnu.backend.rag.service;

import com.hnu.backend.rag.answer.AnswerGenerator;
import com.hnu.backend.rag.answer.AnswerResult;
import com.hnu.backend.rag.answer.AnswerStage;
import com.hnu.backend.rag.answer.ContextBuilder;
import com.hnu.backend.rag.configuration.RagProperties;
import com.hnu.backend.rag.prompt.AssembledPrompt;
import com.hnu.backend.rag.prompt.PromptAssemblyStage;
import com.hnu.backend.rag.retrieval.RetrievalService;
import com.hnu.backend.rag.vo.AnswerResponse;
import com.hnu.backend.rag.vo.ModelInfoResponse;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 编排单轮 RAG 问答流程，包括检索、上下文构造、模型生成和引用校验。 */
@Service
public class RagService {
  private static final Logger log = LoggerFactory.getLogger(RagService.class);
  private final RetrievalService retrievalService;
  private final ContextBuilder contexts;
  private final PromptAssemblyStage prompts;
  private final AnswerStage answers;
  private final RagProperties config;

  /**
   * 创建旧单轮问答兼容服务。
   *
   * @param retrievalService 旧单问题知识检索服务
   * @param contexts 来源响应构造器
   * @param prompts 统一提示词组装阶段
   * @param answers 统一最终回答阶段
   * @param config RAG 输入校验配置
   */
  public RagService(
      RetrievalService retrievalService,
      ContextBuilder contexts,
      PromptAssemblyStage prompts,
      AnswerStage answers,
      RagProperties config) {
    this.retrievalService = retrievalService;
    this.contexts = contexts;
    this.prompts = prompts;
    this.answers = answers;
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
    long started = System.nanoTime();
    String normalizedQuestion = question.strip();
    var hits =
        knowledgeBaseIds == null
            ? retrievalService.retrieve(ownerId, normalizedQuestion)
            : retrievalService.retrieve(ownerId, normalizedQuestion, knowledgeBaseIds);
    var context = contexts.build(hits);
    long retrieved = System.nanoTime();
    AssembledPrompt prompt = prompts.assembleLegacy(normalizedQuestion, context);
    AnswerResult result;
    AnswerGenerator.Control control = answers.newControl();
    try {
      result = answers.execute(prompt, AnswerStage.noopObserver(), control);
    } finally {
      // 同步入口结束后主动释放潜在响应流；成功关闭不会改变已经返回的结果。
      control.close();
    }
    AnswerGenerator.Generation generation = result.generation();
    log.info(
        "qa scope={} candidates={} sources={} citations={} provider={} model={} retrievalMs={} generationMs={} totalMs={}",
        knowledgeBaseIds == null ? "all" : knowledgeBaseIds.size(),
        hits.size(),
        result.sources().size(),
        result.citations().size(),
        generation == null ? null : generation.provider(),
        generation == null ? null : generation.model(),
        (retrieved - started) / 1_000_000,
        (System.nanoTime() - retrieved) / 1_000_000,
        (System.nanoTime() - started) / 1_000_000);
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
