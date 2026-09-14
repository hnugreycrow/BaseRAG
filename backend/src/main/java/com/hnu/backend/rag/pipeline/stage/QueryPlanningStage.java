package com.hnu.backend.rag.pipeline.stage;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.rag.model.QueryPlan;
import com.hnu.backend.rag.model.RagMemory;
import com.hnu.backend.rag.model.SubQuestion;
import com.hnu.backend.rag.port.QueryPlanner;
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

@Component
public class QueryPlanningStage {
  private static final Logger log = LoggerFactory.getLogger(QueryPlanningStage.class);

  private final QueryPlanner planner;
  private final RagProperties config;
  private final JsonMapper json = JsonMapper.builder().build();

  public QueryPlanningStage(QueryPlanner planner, RagProperties config) {
    this.planner = planner;
    this.config = config;
  }

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
    return QueryPlan.fallback(originalQuestion);
  }

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

  private String validQuestion(String value) {
    String question = value.strip();
    if (question.isEmpty()
        || question.length() > config.getMaxQuestionChars()
        || question.codePoints().anyMatch(Character::isISOControl)) {
      throw invalid(DegradedReason.INVALID_QUESTION);
    }
    return question;
  }

  private String normalizeForDuplicateCheck(String question) {
    return question.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

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

  private DegradedReason failureReason(RuntimeException error) {
    if (error instanceof ApiException api && "MODEL_TIMEOUT".equals(api.code())) {
      return DegradedReason.MODEL_TIMEOUT;
    }
    return DegradedReason.MODEL_ERROR;
  }

  private long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }

  private PlanValidationException invalid(DegradedReason reason) {
    return new PlanValidationException(reason);
  }

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

  private static final class PlanValidationException extends RuntimeException {
    private final DegradedReason reason;

    private PlanValidationException(DegradedReason reason) {
      this.reason = reason;
    }
  }
}
