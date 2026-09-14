package com.hnu.backend.rag.prompt;

/** 集中提供会话记忆摘要模型使用的系统提示词。 */
public final class MemorySummaryPrompts {
  private static final String SYSTEM = PromptResourceLoader.load("prompts/memory-summary.md");

  /** 工具类不允许实例化。 */
  private MemorySummaryPrompts() {}

  /**
   * 返回会话记忆摘要系统提示词。
   *
   * @return 不包含任何会话数据的可信系统指令
   */
  public static String system() {
    return SYSTEM;
  }
}
