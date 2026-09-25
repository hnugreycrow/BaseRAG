package com.hnu.backend.rag.prompt;

/** 集中提供会话记忆摘要模型使用的系统提示词。 */
public final class MemorySummaryPrompts {
  private static final String SYSTEM = PromptResourceLoader.load("prompts/memory-summary.md");

  /** 工具类不允许实例化。 */
  private MemorySummaryPrompts() {}

  /**
   * 返回带有摘要长度上限的系统提示词。
   *
   * @param maxChars 摘要的 Unicode 字符上限
   * @return 不包含任何会话数据的可信系统指令
   */
  public static String system(int maxChars) {
    if (maxChars < 1) {
      throw new IllegalArgumentException("maxChars must be positive");
    }
    return SYSTEM.replace("{summary_max_chars}", Integer.toString(maxChars));
  }
}
