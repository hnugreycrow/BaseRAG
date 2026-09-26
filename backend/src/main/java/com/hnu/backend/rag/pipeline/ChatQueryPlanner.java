package com.hnu.backend.rag.pipeline;

import com.hnu.backend.common.json.JsonCodecs;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.rag.config.RagProperties;
import com.hnu.backend.rag.generation.QueryPlanningPrompts;
import com.hnu.backend.rag.memory.MemoryTurn;
import com.hnu.backend.rag.memory.RagMemory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 使用统一系统提示词和结构化用户数据调用 Chat 模型的问题规划器。 */
@Component
public class ChatQueryPlanner implements QueryPlanner {
  private static final Logger log = LoggerFactory.getLogger(ChatQueryPlanner.class);
  private final ChatClient chat;
  private final RagProperties config;
  private final JsonMapper json = JsonCodecs.models();

  /**
   * 创建基于通用 Chat 模型的问题规划器。
   *
   * @param chat 模型客户端
   * @param config 规划阶段最近轮数配置
   */
  public ChatQueryPlanner(ChatClient chat, RagProperties config) {
    this.chat = chat;
    this.config = config;
  }

  /**
   * 将最近完整轮次、当前问题和拆分上限作为结构化数据提交给规划模型。
   *
   * @param memory 当前会话记忆
   * @param currentQuestion 当前用户问题
   * @param maxSubQuestions 最大子问题数量
   * @return 未解析的模型输出及模型元数据
   */
  @Override
  public PlanningOutput plan(RagMemory memory, String currentQuestion, int maxSubQuestions) {
    List<MemoryTurn> availableTurns = memory.recentTurns();
    int start =
        Math.max(0, availableTurns.size() - config.getPipeline().getPlanning().getRecentTurns());
    List<MemoryTurn> recentTurns = availableTurns.subList(start, availableTurns.size());
    Map<String, Object> conversationMemory = new LinkedHashMap<>();
    conversationMemory.put("recentTurns", recentTurns);

    Map<String, Object> input = new LinkedHashMap<>();
    input.put("conversationMemory", conversationMemory);
    input.put("currentQuestion", currentQuestion);
    input.put("maxSubQuestions", maxSubQuestions);

    String prompt = json.writeValueAsString(input);
    log.info("query planning input chars={} recentTurns={}", prompt.length(), recentTurns.size());
    ChatClient.Generation generation = chat.generate(QueryPlanningPrompts.system(), prompt);
    return new PlanningOutput(
        generation.content(), generation.id(), generation.provider(), generation.model());
  }
}
