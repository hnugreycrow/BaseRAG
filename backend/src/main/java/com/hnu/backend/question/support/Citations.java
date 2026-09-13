package com.hnu.backend.question.support;

import com.hnu.backend.question.api.SourceResponse;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class Citations {
  private static final Pattern REFERENCE =
      Pattern.compile("\\[\\s*(S[^\\]\\r\\n]*)\\]", Pattern.CASE_INSENSITIVE);

  private Citations() {}

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
