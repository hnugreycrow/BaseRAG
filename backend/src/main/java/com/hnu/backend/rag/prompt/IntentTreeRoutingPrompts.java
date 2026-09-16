package com.hnu.backend.rag.prompt;

/** 集中提供意图树分类模型使用的系统提示词。 */
public final class IntentTreeRoutingPrompts {
  private static final String SYSTEM = PromptResourceLoader.load("prompts/intent-tree-routing.md");

  private IntentTreeRoutingPrompts() {}

  /** 返回不包含问题或意图树数据的可信系统指令。 */
  public static String system() {
    return SYSTEM;
  }
}
