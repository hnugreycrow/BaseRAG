package com.hnu.backend.rag.support;

import com.hnu.backend.rag.vo.SourceResponse;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 回答引用编号的提取与白名单校验工具。 */
public final class Citations {
  private static final Pattern REFERENCE =
      Pattern.compile("\\[\\s*(S[^\\]\\r\\n]*)\\]", Pattern.CASE_INSENSITIVE);

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
    Set<String> allowed = new HashSet<>();
    sources.forEach(source -> allowed.add(source.citationId()));
    Set<String> used = new LinkedHashSet<>();
    var matcher = REFERENCE.matcher(answer);
    while (matcher.find()) {
      String id = matcher.group(1);
      if (!allowed.contains(id)) throw new IllegalArgumentException("Invalid citation");
      used.add(id);
    }
    return List.copyOf(used);
  }
}
