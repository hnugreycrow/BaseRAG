package com.hnu.backend.rag.planning;

import com.hnu.backend.configuration.ObservabilityProperties;
import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.observability.RagDecisionLog;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.TraceReasonCatalog;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.observability.trace.TraceContext;
import com.hnu.backend.rag.memory.RagMemory;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import com.hnu.backend.shared.json.JsonCodecs;
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
  private final ObservabilityProperties observability;
  private final JsonMapper json = JsonCodecs.models();

  /**
   * 创建查询规划阶段。
   *
   * @param planner 查询规划器
   * @param config RAG 配置
   * @param observability 日志内容配置
   */
  public QueryPlanningStage(
      QueryPlanner planner, RagProperties config, ObservabilityProperties observability) {
    this.planner = planner;
    this.config = config;
    this.observability = observability;
  }

  /**
   * 生成并校验查询计划；任何模型或格式异常都会降级为原始问题。
   *
   * @param memory 当前会话记忆
   * @param originalQuestion 原始用户问题
   * @return 可安全执行的查询计划
   */
  public QueryPlan execute(RagMemory memory, String originalQuestion) {
    return execute(memory, originalQuestion, RagRunTrace.noop());
  }

  /**
   * 生成查询计划并把模型信息或降级原因写入当前 Trace。
   *
   * @param memory 当前会话记忆
   * @param originalQuestion 原始用户问题
   * @param trace 当前问答 Trace
   * @return 可安全执行的查询计划
   */
  public QueryPlan execute(RagMemory memory, String originalQuestion, TraceContext trace) {
    return trace.execute(
        RagStageName.QUERY_PLANNING,
        null,
        1,
        span -> {
          long startedAt = System.nanoTime();
          QueryPlanner.PlanningOutput output = null;
          String degradedReason;
          String exceptionType = null;
          try {
            int maxSubQuestions = config.getPipeline().getMaxSubQuestions();
            output = planner.plan(memory, originalQuestion, maxSubQuestions);
            QueryPlan plan = parse(output.content(), maxSubQuestions);
            span.model(output.modelId(), output.provider(), output.model());
            span.success(plan.subQuestions().size());
            logPlan(trace, originalQuestion, plan, "SUCCESS", null, null, output, startedAt);
            return plan;
          } catch (PlanValidationException e) {
            if (output != null) {
              span.model(output.modelId(), output.provider(), output.model());
            }
            span.degraded(1, e.reason.code());
            degradedReason = e.reason.code();
          } catch (RuntimeException e) {
            TraceReasonCatalog reason = failureReason(e);
            span.degraded(1, reason.code());
            degradedReason = reason.code();
            exceptionType = e.getClass().getSimpleName();
          }
          // 规划是增强能力而非问答前置条件，失败时保留原始问题继续执行。
          QueryPlan fallback = QueryPlan.fallback(originalQuestion);
          logPlan(
              trace,
              originalQuestion,
              fallback,
              "FALLBACK",
              degradedReason,
              exceptionType,
              output,
              startedAt);
          return fallback;
        });
  }

  private void logPlan(
      TraceContext trace,
      String originalQuestion,
      QueryPlan plan,
      String status,
      String reason,
      String exceptionType,
      QueryPlanner.PlanningOutput output,
      long startedAt) {
    RagDecisionLog.emit(
        () -> {
          boolean fallback = "FALLBACK".equals(status);
          if (fallback ? !log.isWarnEnabled() : !log.isInfoEnabled()) {
            return;
          }
          String message =
              "query planning result runId="
                  + trace.runId()
                  + " status="
                  + status
                  + " reason="
                  + reason
                  + " exceptionType="
                  + exceptionType
                  + " originalLength="
                  + originalQuestion.length()
                  + " standaloneLength="
                  + plan.standaloneQuestion().length()
                  + " rewritten="
                  + !originalQuestion.strip().equals(plan.standaloneQuestion())
                  + " subQuestions="
                  + plan.subQuestions().size()
                  + " provider="
                  + (output == null ? null : output.provider())
                  + " model="
                  + (output == null ? null : output.model())
                  + " planningMs="
                  + elapsedMillis(startedAt);
          if (observability.isLogQuestionContent()) {
            message +=
                " originalQuestion="
                    + json.writeValueAsString(originalQuestion)
                    + " standaloneQuestion="
                    + json.writeValueAsString(plan.standaloneQuestion())
                    + " subQuestionsDetail="
                    + json.writeValueAsString(plan.subQuestions());
          }
          if (fallback) {
            log.warn(message);
          } else {
            log.info(message);
          }
        });
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
      throw invalid(TraceReasonCatalog.EMPTY_OUTPUT);
    }
    JsonNode root;
    try {
      root = json.readTree(stripFence(rawContent));
    } catch (RuntimeException e) {
      throw invalid(TraceReasonCatalog.INVALID_JSON);
    }
    if (root == null
        || !root.isObject()
        || root.size() != 2
        || !root.path("standaloneQuestion").isString()
        || !root.path("subQuestions").isArray()) {
      throw invalid(TraceReasonCatalog.INVALID_SCHEMA);
    }

    String standaloneQuestion = validQuestion(root.path("standaloneQuestion").asString());
    JsonNode subQuestionsNode = root.path("subQuestions");
    if (subQuestionsNode.isEmpty()) {
      throw invalid(TraceReasonCatalog.EMPTY_SUBQUESTIONS);
    }
    if (subQuestionsNode.size() > maxSubQuestions) {
      throw invalid(TraceReasonCatalog.TOO_MANY_SUBQUESTIONS);
    }

    List<SubQuestion> subQuestions = new ArrayList<>();
    Set<String> normalizedQuestions = new HashSet<>();
    int index = 1;
    for (JsonNode node : subQuestionsNode) {
      if (!node.isObject()
          || node.size() != 2
          || !node.path("id").isString()
          || !node.path("question").isString()) {
        throw invalid(TraceReasonCatalog.INVALID_SCHEMA);
      }
      // 强制使用连续 Q1、Q2……，使后续执行结果可以稳定关联到子问题。
      String expectedId = "Q" + index;
      if (!expectedId.equals(node.path("id").asString())) {
        throw invalid(TraceReasonCatalog.INVALID_SUBQUESTION_ID);
      }
      String question = validQuestion(node.path("question").asString());
      if (!normalizedQuestions.add(normalizeForDuplicateCheck(question))) {
        throw invalid(TraceReasonCatalog.DUPLICATE_SUBQUESTION);
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
      throw invalid(TraceReasonCatalog.INVALID_QUESTION);
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
    if (!content.startsWith("```")) {
      return content;
    }
    int firstLine = content.indexOf('\n');
    if (firstLine < 0 || !content.endsWith("```")) {
      throw invalid(TraceReasonCatalog.INVALID_JSON);
    }
    String opener = content.substring(0, firstLine).strip();
    if (!("```".equals(opener) || "```json".equalsIgnoreCase(opener))) {
      throw invalid(TraceReasonCatalog.INVALID_JSON);
    }
    String body = content.substring(firstLine + 1, content.length() - 3).strip();
    if (body.isEmpty() || body.contains("```")) {
      throw invalid(TraceReasonCatalog.INVALID_JSON);
    }
    return body;
  }

  /**
   * 将运行时异常归类为可观测的规划降级原因。
   *
   * @param error 规划异常
   * @return 降级原因
   */
  private TraceReasonCatalog failureReason(RuntimeException error) {
    if (error instanceof ApiException api && ErrorCode.MODEL_TIMEOUT.code().equals(api.code())) {
      return TraceReasonCatalog.MODEL_TIMEOUT;
    }
    return TraceReasonCatalog.MODEL_ERROR;
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
  private PlanValidationException invalid(TraceReasonCatalog reason) {
    return new PlanValidationException(reason);
  }

  /** 在解析流程中传递降级原因的轻量内部异常。 */
  private static final class PlanValidationException extends RuntimeException {
    private final TraceReasonCatalog reason;

    /**
     * 创建计划校验异常。
     *
     * @param reason 降级原因
     */
    private PlanValidationException(TraceReasonCatalog reason) {
      this.reason = reason;
    }
  }

  /** 兼容根 Trace 入口；内部显式传递父节点上下文。 */
  public QueryPlan execute(RagMemory memory, String originalQuestion, RagRunTrace trace) {
    return execute(memory, originalQuestion, trace.context());
  }
}
