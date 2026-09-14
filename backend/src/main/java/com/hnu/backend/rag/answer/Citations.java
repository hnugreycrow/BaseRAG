package com.hnu.backend.rag.answer;

import com.hnu.backend.rag.vo.SourceResponse;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 回答引用编号的提取与白名单校验工具。 */
public final class Citations {
  private static final Pattern KNOWLEDGE_REFERENCE =
      Pattern.compile("\\[\\s*(S[^\\]\\r\\n]*)\\]", Pattern.CASE_INSENSITIVE);
  private static final Pattern TOOL_REFERENCE = Pattern.compile("工具\\s*(T\\d+)");

  /** 禁止实例化只提供静态引用校验能力的工具类。 */
  private Citations() {}

  /**
   * 提取回答中的引用，并确认每个引用均来自本轮提供的来源。
   *
   * @param answer 模型回答
   * @param sources 本轮允许引用的来源
   * @return 按首次出现顺序去重后的引用编号
   * @throws IllegalArgumentException 回答包含未提供的引用编号时抛出
   */
  public static List<String> validate(String answer, List<SourceResponse> sources) {
    return validate(answer, sources, List.of()).citations();
  }

  /**
   * 分别提取知识引用与工具编号，并确认它们均来自本轮提示词白名单。
   *
   * @param answer 模型回答
   * @param sources 本轮允许引用的知识来源
   * @param toolReferenceIds 本轮允许标记的工具编号
   * @return 按首次出现顺序去重后的知识引用和工具编号
   * @throws IllegalArgumentException 回答包含未提供的知识引用或工具编号时抛出
   */
  public static Validation validate(
      String answer, List<SourceResponse> sources, List<String> toolReferenceIds) {
    Set<String> allowed = new HashSet<>();
    sources.forEach(source -> allowed.add(source.citationId()));
    Set<String> used = new LinkedHashSet<>();
    var matcher = KNOWLEDGE_REFERENCE.matcher(answer);
    while (matcher.find()) {
      String id = matcher.group(1);
      if (!allowed.contains(id)) throw new IllegalArgumentException("Invalid citation");
      used.add(id);
    }
    Set<String> allowedTools = new HashSet<>(toolReferenceIds);
    Set<String> usedTools = new LinkedHashSet<>();
    var toolMatcher = TOOL_REFERENCE.matcher(answer);
    while (toolMatcher.find()) {
      String id = toolMatcher.group(1);
      if (!allowedTools.contains(id)) throw new IllegalArgumentException("Invalid tool reference");
      usedTools.add(id);
    }
    return new Validation(List.copyOf(used), List.copyOf(usedTools));
  }

  /**
   * 已通过白名单校验的回答引用。
   *
   * @param citations 知识来源的 S 编号
   * @param toolReferences 工具观察的 T 编号
   */
  public record Validation(List<String> citations, List<String> toolReferences) {
    /** 冻结两个引用集合，避免校验结果被调用方修改。 */
    public Validation {
      citations = List.copyOf(citations);
      toolReferences = List.copyOf(toolReferences);
    }
  }
}
