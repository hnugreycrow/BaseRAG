package com.hnu.backend.rag.planning;

import com.hnu.backend.rag.memory.RagMemory;

/** 使用模型将上下文相关问题改写并拆解为检索计划。 */
public interface QueryPlanner {
  /**
   * 生成查询计划的模型原始输出。
   *
   * @param memory 当前会话记忆
   * @param currentQuestion 当前用户问题
   * @param maxSubQuestions 允许生成的最大子问题数
   * @return 原始输出及实际使用的模型信息
   */
  PlanningOutput plan(RagMemory memory, String currentQuestion, int maxSubQuestions);

  /**
   * 查询规划模型的一次原始输出。
   *
   * @param content 模型返回的计划文本
   * @param modelId 模型配置标识
   * @param provider 模型供应商
   * @param model 模型名称
   */
  record PlanningOutput(String content, String modelId, String provider, String model) {}
}
