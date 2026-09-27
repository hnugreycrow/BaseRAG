package com.hnu.backend.common.exception;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 将异常渲染为不包含异常消息和业务正文的可诊断堆栈。 */
public final class SafeExceptionLog {
  private SafeExceptionLog() {}

  /**
   * 输出异常类型、调用栈、被抑制异常和原因链，不输出任何异常消息。
   *
   * @param error 待记录的异常；为 {@code null} 时返回空字符串
   * @return 可用于诊断的脱敏堆栈文本
   */
  public static String render(Throwable error) {
    StringBuilder output = new StringBuilder();
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    append(output, error, "", visited);
    return output.toString();
  }

  private static void append(
      StringBuilder output, Throwable error, String prefix, Set<Throwable> visited) {
    if (error == null) {
      return;
    }
    if (!visited.add(error)) {
      output.append(prefix).append("[circular cause]");
      return;
    }
    output.append(prefix).append(error.getClass().getName());
    for (StackTraceElement frame : error.getStackTrace()) {
      output.append(System.lineSeparator()).append("\tat ").append(frame);
    }
    for (Throwable suppressed : error.getSuppressed()) {
      output.append(System.lineSeparator());
      append(output, suppressed, "Suppressed: ", visited);
    }
    if (error.getCause() != null) {
      output.append(System.lineSeparator());
      append(output, error.getCause(), "Caused by: ", visited);
    }
  }
}
