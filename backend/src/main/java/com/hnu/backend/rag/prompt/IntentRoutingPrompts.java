package com.hnu.backend.rag.prompt;

/** 集中提供子问题意图识别模型使用的系统提示词。 */
public final class IntentRoutingPrompts {
  private static final String SYSTEM = PromptResourceLoader.load("prompts/intent-routing.md");

  /** 工具类不允许实例化。 */
  private IntentRoutingPrompts() {}

  /**
   * 返回意图路由系统提示词。
   *
   * @return 不包含问题和工具目录的可信系统指令
   */
  public static String system() {
    return SYSTEM;
  }
}
