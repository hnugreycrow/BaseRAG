package com.hnu.backend.rag.pipeline.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.rag.model.QueryPlan;
import com.hnu.backend.rag.model.RagMemory;
import com.hnu.backend.rag.model.SubQuestion;
import com.hnu.backend.rag.port.QueryPlanner;
import com.hnu.backend.shared.error.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class QueryPlanningStageTest {
  private static final String ORIGINAL = "原问题";

  private final QueryPlanner planner = mock(QueryPlanner.class);
  private final RagProperties config = new RagProperties();
  private final QueryPlanningStage stage = new QueryPlanningStage(planner, config);
  private final RagMemory emptyMemory = new RagMemory("{}", 0, List.of(), List.of(), 0);
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void acceptsSimpleAndComparisonQuestionsWithoutForcedSplitting() {
    stub(plan("公司的请假制度是什么？", sub("Q1", "公司的请假制度是什么？")));

    QueryPlan simple = stage.execute(emptyMemory, ORIGINAL);

    assertEquals(1, simple.subQuestions().size());
    assertEquals("公司的请假制度是什么？", simple.standaloneQuestion());

    stub(plan("比较制度 A 和制度 B", sub("Q1", "比较制度 A 和制度 B")));

    QueryPlan comparison = stage.execute(emptyMemory, ORIGINAL);

    assertEquals(1, comparison.subQuestions().size());
    assertEquals("比较制度 A 和制度 B", comparison.subQuestions().getFirst().question());
  }

  @Test
  void acceptsMultipleIndependentSubQuestions() {
    stub(plan("分别说明年假政策和报销时限", sub("Q1", "年假政策是什么？"), sub("Q2", "报销提交时限是什么？")));

    QueryPlan result = stage.execute(emptyMemory, ORIGINAL);

    assertEquals("分别说明年假政策和报销时限", result.standaloneQuestion());
    assertEquals(
        List.of("年假政策是什么？", "报销提交时限是什么？"),
        result.subQuestions().stream().map(SubQuestion::question).toList());
  }

  @Test
  void plansEvenWhenMemoryHasNoCompletedTurns() {
    stub(plan("独立问题", sub("Q1", "独立问题")));

    stage.execute(emptyMemory, ORIGINAL);

    verify(planner).plan(emptyMemory, ORIGINAL, 4);
  }

  @Test
  void acceptsOneStandardJsonFence() {
    stub("```json\n" + plan("独立问题", sub("Q1", "独立问题")) + "\n```");

    QueryPlan result = stage.execute(emptyMemory, ORIGINAL);

    assertEquals("独立问题", result.standaloneQuestion());
  }

  @Test
  void degradesMalformedOrUnexpectedSchemas() {
    List<String> invalidCandidates =
        List.of(
            "not-json",
            "说明\n" + plan("独立问题", sub("Q1", "独立问题")),
            "{\"standaloneQuestion\":\"独立问题\"}",
            "{\"standaloneQuestion\":1,\"subQuestions\":[]}",
            "{\"standaloneQuestion\":\"独立问题\",\"subQuestions\":[],\"extra\":true}",
            "{\"standaloneQuestion\":\"独立问题\",\"subQuestions\":[{\"id\":\"Q1\",\"question\":\"独立问题\",\"dependsOn\":[]}]}",
            "```yaml\n{}\n```",
            "```json\n{}\n```\nextra");

    for (String candidate : invalidCandidates) {
      stub(candidate);
      assertFallback(stage.execute(emptyMemory, ORIGINAL));
    }
  }

  @Test
  void degradesEmptyTooManyMisnumberedAndDuplicateSubQuestions() {
    config.getPipeline().setMaxSubQuestions(2);
    List<String> invalidCandidates =
        List.of(
            plan("独立问题"),
            plan("三个目标", sub("Q1", "问题一"), sub("Q2", "问题二"), sub("Q3", "问题三")),
            plan("编号错误", sub("Q2", "问题一")),
            plan("重复问题", sub("Q1", "同一 问题"), sub("Q2", "同一   问题")));

    for (String candidate : invalidCandidates) {
      stub(candidate);
      assertFallback(stage.execute(emptyMemory, ORIGINAL));
    }
  }

  @Test
  void degradesBlankControlAndOversizedQuestions() {
    String oversized = "甲".repeat(config.getMaxQuestionChars() + 1);
    List<String> invalidCandidates =
        List.of(
            plan(" ", sub("Q1", "问题")),
            plan("独立问题", sub("Q1", " ")),
            plan("独立\u0001问题", sub("Q1", "问题")),
            plan("独立问题", sub("Q1", oversized)));

    for (String candidate : invalidCandidates) {
      stub(candidate);
      assertFallback(stage.execute(emptyMemory, ORIGINAL));
    }
  }

  @Test
  void degradesModelErrorsAndTimeouts() {
    when(planner.plan(any(RagMemory.class), anyString(), eq(4)))
        .thenThrow(new RuntimeException("unavailable"));
    assertFallback(stage.execute(emptyMemory, ORIGINAL));

    when(planner.plan(any(RagMemory.class), anyString(), eq(4)))
        .thenThrow(ApiException.upstream("MODEL_TIMEOUT", "timeout"));
    assertFallback(stage.execute(emptyMemory, ORIGINAL));
  }

  private void stub(String content) {
    when(planner.plan(
            any(RagMemory.class), anyString(), eq(config.getPipeline().getMaxSubQuestions())))
        .thenReturn(new QueryPlanner.PlanningOutput(content, "planner", "test", "test-model"));
  }

  @SafeVarargs
  private final String plan(String standaloneQuestion, Map<String, Object>... subQuestions) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("standaloneQuestion", standaloneQuestion);
    value.put("subQuestions", List.of(subQuestions));
    return json.writeValueAsString(value);
  }

  private Map<String, Object> sub(String id, String question) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("id", id);
    value.put("question", question);
    return value;
  }

  private void assertFallback(QueryPlan result) {
    assertEquals(ORIGINAL, result.standaloneQuestion());
    assertEquals(List.of(new SubQuestion("Q1", ORIGINAL)), result.subQuestions());
  }
}
