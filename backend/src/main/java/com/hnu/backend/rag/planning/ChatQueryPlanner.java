package com.hnu.backend.rag.planning;

import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.rag.memory.RagMemory;
import com.hnu.backend.rag.prompt.QueryPlanningPrompts;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 使用统一系统提示词和结构化用户数据调用 Chat 模型的问题规划器。 */
@Component
public class ChatQueryPlanner implements QueryPlanner {
  private final ChatClient chat;
  private final JsonMapper json = JsonMapper.builder().build();

  /**
   * 创建基于通用 Chat 模型的问题规划器。
   *
   * @param chat 模型客户端
   */
  public ChatQueryPlanner(ChatClient chat) {
    this.chat = chat;
  }

  /**
   * 将会话记忆、当前问题和拆分上限作为结构化数据提交给规划模型。
   *
   * @param memory 当前会话记忆
   * @param currentQuestion 当前用户问题
   * @param maxSubQuestions 最大子问题数量
   * @return 未解析的模型输出及模型元数据
   */
  @Override
  public PlanningOutput plan(RagMemory memory, String currentQuestion, int maxSubQuestions) {
    Map<String, Object> conversationMemory = new LinkedHashMap<>();
    conversationMemory.put("summary", memory.summary());
    conversationMemory.put("summaryRevision", memory.summaryRevision());
    conversationMemory.put("unsummarizedTurns", memory.unsummarizedTurns());
    conversationMemory.put("recentTurns", memory.recentTurns());
    conversationMemory.put("loadedThroughTurn", memory.loadedThroughTurn());

    Map<String, Object> input = new LinkedHashMap<>();
    input.put("conversationMemory", conversationMemory);
    input.put("currentQuestion", currentQuestion);
    input.put("maxSubQuestions", maxSubQuestions);

    ChatClient.Generation generation =
        chat.generate(QueryPlanningPrompts.system(), json.writeValueAsString(input));
    return new PlanningOutput(
        generation.content(), generation.id(), generation.provider(), generation.model());
  }
}
