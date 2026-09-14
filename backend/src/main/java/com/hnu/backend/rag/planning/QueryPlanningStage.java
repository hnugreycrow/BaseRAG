package com.hnu.backend.rag.planning;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.rag.memory.RagMemory;
import com.hnu.backend.shared.error.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 查询规划阶段：校验模型生成的严格计划，并在异常时安全降级。 */
@Component
public class QueryPlanningStage {
  private static final Logger log = LoggerFactory.getLogger(QueryPlanningStage.class);

  private final QueryPlanner planner;
  private final RagProperties config;
  private final JsonMapper json = JsonMapper.builder().build();

  /**
   * 创建查询规划阶段。
   *
   * @param planner 查询规划器
   * @param config RAG 配置
   */
  public QueryPlanningStage(QueryPlanner planner, RagProperties config) {
    this.planner = planner;
    this.config = config;
  }

  /**
   * 生成并校验查询计划；任何模型或格式异常都会降级为原始问题。
   *
   * @param memory 当前会话记忆
   * @param originalQuestion 原始用户问题
   * @return 可安全执行的查询计划
   */
  public QueryPlan execute(RagMemory memory, String originalQuestion) {
    long startedAt = System.nanoTime();
    QueryPlanner.PlanningOutput output = null;
    try {
      int maxSubQuestions = config.getPipeline().getMaxSubQuestions();
      output = planner.plan(memory, originalQuestion, maxSubQuestions);
      QueryPlan plan = parse(output.content(), maxSubQuestions);
      log.info(
          "query planning completed subQuestions={} provider={} model={} planningMs={}",
          plan.subQuestions().size(),
          output.provider(),
          output.model(),
          elapsedMillis(startedAt));
      return plan;
    } catch (PlanValidationException e) {
      log.warn(
          "query planning degraded reason={} modelId={} planningMs={}",
          e.reason,
          output == null ? null : output.modelId(),
          elapsedMillis(startedAt));
    } catch (RuntimeException e) {
      log.warn(
          "query planning degraded reason={} exceptionType={} planningMs={}",
          failureReason(e),
          e.getClass().getSimpleName(),
          elapsedMillis(startedAt));
    }
    // 规划是增强能力而非问答前置条件，失败时保留原始问题继续执行。
    return QueryPlan.fallback(originalQuestion);
  }

  /**
   * 将模型输出解析为严格的查询计划。
   *
   * @param rawContent 模型原始文本
   * @param maxSubQuestions 最大子问题数
   * @return 已校验的查询计划
   */
  private QueryPlan parse(String rawContent, int maxSubQuestions) {
    if (rawContent == null || rawContent.isBlank()) {
      throw invalid(DegradedReason.EMPTY_OUTPUT);
    }
    JsonNode root;
    try {
      root = json.readTree(stripFence(rawContent));
    } catch (RuntimeException e) {
      throw invalid(DegradedReason.INVALID_JSON);
    }
    if (root == null
        || !root.isObject()
        || root.size() != 2
        || !root.path("standaloneQuestion").isTextual()
        || !root.path("subQuestions").isArray()) {
      throw invalid(DegradedReason.INVALID_SCHEMA);
    }

    String standaloneQuestion = validQuestion(root.path("standaloneQuestion").asString());
    JsonNode subQuestionsNode = root.path("subQuestions");
    if (subQuestionsNode.isEmpty()) throw invalid(DegradedReason.EMPTY_SUBQUESTIONS);
    if (subQuestionsNode.size() > maxSubQuestions) {
      throw invalid(DegradedReason.TOO_MANY_SUBQUESTIONS);
    }

    List<SubQuestion> subQuestions = new ArrayList<>();
    Set<String> normalizedQuestions = new HashSet<>();
    int index = 1;
    for (JsonNode node : subQuestionsNode) {
      if (!node.isObject()
          || node.size() != 2
          || !node.path("id").isTextual()
          || !node.path("question").isTextual()) {
        throw invalid(DegradedReason.INVALID_SCHEMA);
      }
      // 强制使用连续 Q1、Q2……，使后续执行结果可以稳定关联到子问题。
      String expectedId = "Q" + index;
      if (!expectedId.equals(node.path("id").asString())) {
        throw invalid(DegradedReason.INVALID_SUBQUESTION_ID);
      }
      String question = validQuestion(node.path("question").asString());
      if (!normalizedQuestions.add(normalizeForDuplicateCheck(question))) {
        throw invalid(DegradedReason.DUPLICATE_SUBQUESTION);
      }
      subQuestions.add(new SubQuestion(expectedId, question));
      index++;
    }
    return new QueryPlan(standaloneQuestion, subQuestions);
  }

  /**
   * 清理并校验模型生成的问题文本。
   *
   * @param value 待校验文本
   * @return 去除首尾空白后的问题
   */
  private String validQuestion(String value) {
    String question = value.strip();
    if (question.isEmpty()
        || question.length() > config.getMaxQuestionChars()
        || question.codePoints().anyMatch(Character::isISOControl)) {
      throw invalid(DegradedReason.INVALID_QUESTION);
    }
    return question;
  }

  /**
   * 归一化问题以检测仅大小写或空白不同的重复项。
   *
   * @param question 问题文本
   * @return 用于判重的文本
   */
  private String normalizeForDuplicateCheck(String question) {
    return question.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  /**
   * 去除模型可能附加的单层 JSON Markdown 代码围栏。
   *
   * @param rawContent 模型原始文本
   * @return 围栏内部文本或原文本
   */
  private String stripFence(String rawContent) {
    String content = rawContent.strip();
    if (!content.startsWith("```")) return content;
    int firstLine = content.indexOf('\n');
    if (firstLine < 0 || !content.endsWith("```")) {
      throw invalid(DegradedReason.INVALID_JSON);
    }
    String opener = content.substring(0, firstLine).strip();
    if (!("```".equals(opener) || "```json".equalsIgnoreCase(opener))) {
      throw invalid(DegradedReason.INVALID_JSON);
    }
    String body = content.substring(firstLine + 1, content.length() - 3).strip();
    if (body.isEmpty() || body.contains("```")) {
      throw invalid(DegradedReason.INVALID_JSON);
    }
    return body;
  }

  /**
   * 将运行时异常归类为可观测的规划降级原因。
   *
   * @param error 规划异常
   * @return 降级原因
   */
  private DegradedReason failureReason(RuntimeException error) {
    if (error instanceof ApiException api && "MODEL_TIMEOUT".equals(api.code())) {
      return DegradedReason.MODEL_TIMEOUT;
    }
    return DegradedReason.MODEL_ERROR;
  }

  /**
   * 计算阶段耗时。
   *
   * @param startedAt {@link System#nanoTime()} 获取的开始时间
   * @return 毫秒耗时
   */
  private long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }

  /**
   * 创建携带结构化降级原因的内部校验异常。
   *
   * @param reason 降级原因
   * @return 计划校验异常
   */
  private PlanValidationException invalid(DegradedReason reason) {
    return new PlanValidationException(reason);
  }

  /** 查询规划降级原因，仅用于日志分类。 */
  private enum DegradedReason {
    MODEL_ERROR,
    MODEL_TIMEOUT,
    EMPTY_OUTPUT,
    INVALID_JSON,
    INVALID_SCHEMA,
    INVALID_QUESTION,
    EMPTY_SUBQUESTIONS,
    TOO_MANY_SUBQUESTIONS,
    INVALID_SUBQUESTION_ID,
    DUPLICATE_SUBQUESTION
  }

  /** 在解析流程中传递降级原因的轻量内部异常。 */
  private static final class PlanValidationException extends RuntimeException {
    private final DegradedReason reason;

    /**
     * 创建计划校验异常。
     *
     * @param reason 降级原因
     */
    private PlanValidationException(DegradedReason reason) {
      this.reason = reason;
    }
  }
}
