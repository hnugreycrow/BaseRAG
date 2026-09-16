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
  private static final Pattern LIST_ITEM = Pattern.compile("^ {0,3}(?:[-+*]|\\d+[.)])\\s+.*");
  private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*");

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
    String prose = withoutFencedCode(answer);
    var matcher = KNOWLEDGE_REFERENCE.matcher(prose);
    while (matcher.find()) {
      String id = matcher.group(1);
      if (!allowed.contains(id)) throw new IllegalArgumentException("Invalid citation");
      used.add(id);
    }
    Set<String> allowedTools = new HashSet<>(toolReferenceIds);
    Set<String> usedTools = new LinkedHashSet<>();
    var toolMatcher = TOOL_REFERENCE.matcher(prose);
    while (toolMatcher.find()) {
      String id = toolMatcher.group(1);
      if (!allowedTools.contains(id)) throw new IllegalArgumentException("Invalid tool reference");
      usedTools.add(id);
    }
    return new Validation(List.copyOf(used), List.copyOf(usedTools));
  }

  /**
   * 将每段或每个列表项内的合法知识引用按首次出现顺序移到末尾；代码围栏保持原样。
   *
   * @param answer 已通过白名单校验的完整回答
   * @return 位置规范化后的回答
   */
  public static String normalize(String answer) {
    StringBuilder result = new StringBuilder();
    StringBuilder block = new StringBuilder();
    String fence = null;
    for (String line : answer.split("(?<=\\n)", -1)) {
      String marker = fenceMarker(line);
      if (fence != null) {
        result.append(line);
        if (marker != null
            && marker.charAt(0) == fence.charAt(0)
            && marker.length() >= fence.length()) {
          fence = null;
        }
      } else if (marker != null) {
        appendBlock(result, block);
        result.append(line);
        fence = marker;
      } else if (line.isBlank()) {
        appendBlock(result, block);
        result.append(line);
      } else {
        if (LIST_ITEM.matcher(line.stripTrailing()).matches()) appendBlock(result, block);
        block.append(line);
      }
    }
    appendBlock(result, block);
    return result.toString();
  }

  /** 暂时遮蔽代码块，防止代码示例里的 S/T 字样被当成回答引用。 */
  private static String withoutFencedCode(String answer) {
    StringBuilder prose = new StringBuilder();
    String fence = null;
    for (String line : answer.split("(?<=\\n)", -1)) {
      String marker = fenceMarker(line);
      if (fence != null) {
        if (marker != null
            && marker.charAt(0) == fence.charAt(0)
            && marker.length() >= fence.length()) {
          fence = null;
        }
      } else if (marker != null) {
        fence = marker;
      } else {
        prose.append(line);
      }
    }
    return prose.toString();
  }

  private static String fenceMarker(String line) {
    var matcher = FENCE.matcher(line.stripTrailing());
    return matcher.matches() ? matcher.group(1) : null;
  }

  /** 仅移除知识引用；保留块内文字、工具标记和换行。 */
  private static void appendBlock(StringBuilder result, StringBuilder block) {
    if (block.isEmpty()) return;
    String raw = block.toString();
    var matcher = KNOWLEDGE_REFERENCE.matcher(raw);
    Set<String> references = new LinkedHashSet<>();
    StringBuilder clean = new StringBuilder();
    int end = 0;
    while (matcher.find()) {
      clean.append(raw, end, matcher.start());
      references.add(matcher.group(1));
      end = matcher.end();
      // 引用原本紧邻标点时，移走标记也要一并清理标点前留下的分隔空格。
      if (end < raw.length() && ("，。！？；：,.!?;:\r\n".indexOf(raw.charAt(end)) >= 0)) {
        while (!clean.isEmpty() && clean.charAt(clean.length() - 1) == ' ') {
          clean.setLength(clean.length() - 1);
        }
      }
    }
    clean.append(raw, end, raw.length());
    if (references.isEmpty()) {
      result.append(raw);
    } else {
      String content = clean.toString();
      int tail = content.length();
      while (tail > 0 && Character.isWhitespace(content.charAt(tail - 1))) tail--;
      result.append(content, 0, tail);
      result.append(' ');
      for (String reference : references) result.append('[').append(reference).append(']');
      // 被移除引用前的空格不能遗留在块尾；仅保留结构性换行。
      for (int index = tail; index < content.length(); index++) {
        char value = content.charAt(index);
        if (value == '\n' || value == '\r') result.append(value);
      }
    }
    block.setLength(0);
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
