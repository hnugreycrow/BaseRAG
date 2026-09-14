package com.hnu.backend.rag.prompt;

import com.hnu.backend.rag.answer.ContextBuilder;
import com.hnu.backend.rag.execution.ExecutionResult;
import com.hnu.backend.rag.execution.SubQuestionExecution;
import com.hnu.backend.rag.mcp.ToolObservation;
import com.hnu.backend.rag.memory.RagMemory;
import com.hnu.backend.rag.planning.QueryPlan;
import com.hnu.backend.rag.retrieval.EvidenceCandidate;
import com.hnu.backend.rag.routing.IntentRoute;
import com.hnu.backend.rag.routing.IntentType;
import com.hnu.backend.rag.routing.RoutingPlan;
import com.hnu.backend.rag.routing.RoutingReasonCode;
import com.hnu.backend.rag.vo.SourceResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 将流水线中间结果组装为边界清晰、顺序稳定且可直接交给模型的两条消息。 */
@Component
public class PromptAssemblyStage {
  private static final String EMPTY_SUMMARY =
      "{\"goalsAndTopics\":[],\"factsAndConstraints\":[],\"decisionsAndPreferences\":[],"
          + "\"entitiesAndReferences\":[],\"openItems\":[]}";

  private final ContextBuilder contexts;
  private final JsonMapper json = JsonMapper.builder().build();

  /**
   * 创建提示词组装阶段。
   *
   * @param contexts 将内部证据候选转换为现有来源响应的构造器
   */
  public PromptAssemblyStage(ContextBuilder contexts) {
    this.contexts = contexts;
  }

  /**
   * 组装完整流水线的知识、工具和闲聊混合输入。
   *
   * @param memory 本轮加载的会话记忆
   * @param originalQuestion 用户未经改写的原始问题
   * @param plan 经过校验的问题规划
   * @param routing 经过服务端安全归一化的路由计划
   * @param execution 子问题执行结果和本轮冻结预算
   * @param selectedCandidates 重排阶段最终选中的知识证据
   * @return 可直接传给回答模型的提示词快照
   */
  public AssembledPrompt assemblePipeline(
      RagMemory memory,
      String originalQuestion,
      QueryPlan plan,
      RoutingPlan routing,
      ExecutionResult execution,
      List<EvidenceCandidate> selectedCandidates) {
    Objects.requireNonNull(execution, "execution");
    List<SourceResponse> sources = contexts.buildEvidence(selectedCandidates).sources();
    PackedTools packedTools = packTools(execution);
    boolean shouldGenerate =
        !sources.isEmpty()
            || routing.routes().stream()
                .anyMatch(route -> route.intent() != IntentType.KNOWLEDGE_RETRIEVAL);
    return assemble(
        AnswerPrompts.knowledge(),
        memoryView(memory),
        questionPlanView(plan, routing, execution),
        sources,
        packedTools,
        originalQuestion,
        shouldGenerate);
  }

  /**
   * 组装全部子问题都被路由为系统闲聊时的模型输入。
   *
   * @param memory 本轮加载的会话记忆
   * @param originalQuestion 用户未经改写的原始问题
   * @param plan 经过校验的问题规划
   * @param routing 全部为系统闲聊的路由计划
   * @return 不包含虚假证据或工具结果的提示词快照
   */
  public AssembledPrompt assembleSystemChat(
      RagMemory memory, String originalQuestion, QueryPlan plan, RoutingPlan routing) {
    if (!routing.systemChatOnly()) {
      throw new IllegalArgumentException("System chat prompt requires system-chat-only routing");
    }
    return assemble(
        AnswerPrompts.systemChat(),
        memoryView(memory),
        questionPlanView(plan, routing, null),
        List.of(),
        PackedTools.empty(),
        originalQuestion,
        true);
  }

  /**
   * 为旧单轮 RAG 兼容入口组装与新流水线相同结构的模型输入。
   *
   * @param originalQuestion 用户原始问题
   * @param context 旧检索链路已经构造的知识来源
   * @return 与新流水线数据区结构一致的提示词快照
   */
  public AssembledPrompt assembleLegacy(String originalQuestion, ContextBuilder.Context context) {
    List<SourceResponse> sources = context.sources();
    QueryPlan plan = QueryPlan.fallback(originalQuestion);
    RoutingPlan routing =
        new RoutingPlan(
            List.of(
                IntentRoute.knowledgeFallback(
                    "Q1", 1, RoutingReasonCode.KNOWLEDGE_SOURCE_REQUIRED)));
    return assemble(
        AnswerPrompts.knowledge(),
        memoryView(new RagMemory(EMPTY_SUMMARY, 0, List.of(), List.of(), 0)),
        questionPlanView(plan, routing, null),
        sources,
        PackedTools.empty(),
        originalQuestion,
        !sources.isEmpty());
  }

  /**
   * 在不引入上一次错误回答的前提下切换为引用修复系统提示词。
   *
   * @param original 首次生成使用的完整提示词快照
   * @return 用户数据和来源完全不变的引用修复快照
   */
  public AssembledPrompt forCitationRepair(AssembledPrompt original) {
    return new AssembledPrompt(
        AnswerPrompts.citationRepair(),
        original.userPrompt(),
        original.sources(),
        original.toolReferenceIds(),
        original.shouldGenerate());
  }

  /**
   * 按固定键顺序序列化最终用户消息，并把原始问题放在最后一个数据区。
   *
   * @param systemPrompt 可信系统规则
   * @param memory 会话记忆数据区
   * @param questionPlan 问题规划数据区
   * @param sources 实际进入模型的知识证据
   * @param tools 实际进入模型的工具观察
   * @param originalQuestion 用户原始问题
   * @param shouldGenerate 是否需要调用模型
   * @return 冻结后的模型输入
   */
  private AssembledPrompt assemble(
      String systemPrompt,
      Map<String, Object> memory,
      Map<String, Object> questionPlan,
      List<SourceResponse> sources,
      PackedTools tools,
      String originalQuestion,
      boolean shouldGenerate) {
    LinkedHashMap<String, Object> input = new LinkedHashMap<>();
    input.put("conversationMemory", memory);
    input.put("questionPlan", questionPlan);
    input.put("knowledgeEvidence", sources);
    input.put("toolObservations", tools.observations());
    // 原问题固定置于序列化消息末尾，帮助模型在读完长证据后重新聚焦回答目标。
    input.put("answerTarget", Map.of("originalQuestion", originalQuestion));
    return new AssembledPrompt(
        systemPrompt,
        json.writeValueAsString(input),
        sources,
        tools.referenceIds(),
        shouldGenerate);
  }

  /**
   * 将会话记忆转换为不带提示指令的纯数据视图。
   *
   * @param memory RAG 中立会话记忆
   * @return 保持语义字段稳定的有序数据对象
   */
  private Map<String, Object> memoryView(RagMemory memory) {
    LinkedHashMap<String, Object> value = new LinkedHashMap<>();
    value.put("summary", summaryValue(memory.summary()));
    value.put("summaryRevision", memory.summaryRevision());
    value.put("unsummarizedTurns", memory.unsummarizedTurns());
    value.put("recentTurns", memory.recentTurns());
    value.put("loadedThroughTurn", memory.loadedThroughTurn());
    return value;
  }

  /**
   * 把合法摘要 JSON 保持为对象；异常历史数据则作为普通字符串传递，避免被解释成结构指令。
   *
   * @param summary 持久化摘要文本
   * @return JSON 对象或原始字符串
   */
  private Object summaryValue(String summary) {
    try {
      JsonNode parsed = json.readTree(summary);
      return parsed != null && parsed.isObject() ? parsed : summary;
    } catch (RuntimeException ignored) {
      return summary;
    }
  }

  /**
   * 构造不包含原始问题和工具参数的问题规划视图。
   *
   * @param plan 问题规划
   * @param routing 服务端归一化路由
   * @param execution 可选的执行结果；纯闲聊和兼容入口没有执行记录
   * @return 有序的问题规划数据对象
   */
  private Map<String, Object> questionPlanView(
      QueryPlan plan, RoutingPlan routing, ExecutionResult execution) {
    Map<String, SubQuestionExecution> results =
        execution == null
            ? Map.of()
            : execution.subQuestions().stream()
                .collect(
                    Collectors.toMap(
                        SubQuestionExecution::subQuestionId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
    List<Map<String, Object>> routes = new ArrayList<>();
    for (IntentRoute route : routing.routes()) {
      SubQuestionExecution result = results.get(route.subQuestionId());
      LinkedHashMap<String, Object> value = new LinkedHashMap<>();
      value.put("subQuestionId", route.subQuestionId());
      value.put("intent", route.intent());
      value.put("confidence", route.confidence());
      value.put("reasonCode", route.reasonCode());
      value.put("toolName", route.intent() == IntentType.MCP_TOOL ? route.toolHint() : null);
      value.put("executionStatus", result == null ? null : result.status());
      value.put("executionReasonCode", result == null ? null : result.reasonCode());
      routes.add(value);
    }
    LinkedHashMap<String, Object> value = new LinkedHashMap<>();
    value.put("standaloneQuestion", plan.standaloneQuestion());
    value.put("subQuestions", plan.subQuestions());
    value.put("routes", routes);
    return value;
  }

  /**
   * 按子问题执行顺序编号工具结果，避免把参数摘要或内部网关名称发送给模型。
   *
   * @param execution 阶段四执行结果
   * @return 工具数据对象和对应稳定编号
   */
  private PackedTools packTools(ExecutionResult execution) {
    List<Map<String, Object>> observations = new ArrayList<>();
    List<String> referenceIds = new ArrayList<>();
    for (SubQuestionExecution result : execution.subQuestions()) {
      ToolObservation observation = result.toolObservation();
      if (observation == null) continue;
      String referenceId = "T" + (referenceIds.size() + 1);
      LinkedHashMap<String, Object> value = new LinkedHashMap<>();
      value.put("referenceId", referenceId);
      value.put("subQuestionId", result.subQuestionId());
      value.put("toolName", observation.toolName());
      value.put("status", observation.status());
      value.put("reasonCode", observation.reasonCode());
      value.put("elapsedMs", observation.elapsedMs());
      value.put("truncated", observation.truncated());
      value.put("content", observation.content());
      observations.add(value);
      referenceIds.add(referenceId);
    }
    return new PackedTools(observations, referenceIds);
  }

  /**
   * 已移除审计专用字段、可以安全进入最终提示词的工具数据。
   *
   * @param observations 可以直接序列化到 toolObservations 数据区的有序对象
   * @param referenceIds 与 observations 一一对应的稳定 T 编号
   */
  private record PackedTools(List<Map<String, Object>> observations, List<String> referenceIds) {
    /** 冻结内部集合，防止组装完成后被调用方修改。 */
    private PackedTools {
      observations = List.copyOf(observations);
      referenceIds = List.copyOf(referenceIds);
    }

    /**
     * 创建不包含任何工具观察的结果。
     *
     * @return 空工具数据
     */
    private static PackedTools empty() {
      return new PackedTools(List.of(), List.of());
    }
  }
}
