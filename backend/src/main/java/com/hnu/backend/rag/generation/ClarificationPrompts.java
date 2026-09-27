package com.hnu.backend.rag.generation;

/** 集中提供 KB 歧义确认与补充选择识别使用的系统提示词。 */
public final class ClarificationPrompts {
  private static final String DECISION =
      PromptResourceLoader.load("prompts/clarification-decision.md");
  private static final String SELECTION =
      PromptResourceLoader.load("prompts/clarification-selection.md");

  private ClarificationPrompts() {}

  /** 返回批量判断 KB 候选是否需要用户选择的可信系统指令。 */
  public static String decision() {
    return DECISION;
  }

  /** 返回从用户补充中识别唯一允许候选的可信系统指令。 */
  public static String selection() {
    return SELECTION;
  }
}
