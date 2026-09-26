package com.hnu.backend.rag.generation;

/** 集中提供知识回答、系统闲聊和引用修复使用的系统提示词。 */
public final class AnswerPrompts {
  private static final String KNOWLEDGE = PromptResourceLoader.load("prompts/answer-knowledge.md");

  private static final String SYSTEM_CHAT =
      PromptResourceLoader.load("prompts/answer-system-chat.md");

  private static final String CITATION_REPAIR =
      KNOWLEDGE + "\n\n" + PromptResourceLoader.load("prompts/answer-citation-repair.md");

  /** 工具类不允许实例化。 */
  private AnswerPrompts() {}

  /**
   * 返回包含知识证据和工具结果约束的回答系统提示词。
   *
   * @return 知识或混合路由回答的可信系统指令
   */
  public static String knowledge() {
    return KNOWLEDGE;
  }

  /**
   * 返回纯系统闲聊使用的系统提示词。
   *
   * @return 不允许声明检索或工具行为的可信系统指令
   */
  public static String systemChat() {
    return SYSTEM_CHAT;
  }

  /**
   * 返回引用校验失败后重新生成答案使用的系统提示词。
   *
   * @return 包含完整知识回答规则和引用修复约束的可信系统指令
   */
  public static String citationRepair() {
    return CITATION_REPAIR;
  }
}
