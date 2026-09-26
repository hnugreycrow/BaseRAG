package com.hnu.backend.rag.generation;

/** 集中提供问题改写和子问题规划模型使用的系统提示词。 */
public final class QueryPlanningPrompts {
  private static final String SYSTEM = PromptResourceLoader.load("prompts/query-planning.md");

  /** 工具类不允许实例化。 */
  private QueryPlanningPrompts() {}

  /**
   * 返回问题规划系统提示词。
   *
   * @return 不包含会话记忆和用户问题的可信系统指令
   */
  public static String system() {
    return SYSTEM;
  }
}
