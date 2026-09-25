package com.hnu.backend.shared.error;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 将异常渲染为不包含异常消息和业务正文的可诊断堆栈。 */
public final class SafeExceptionLog {
  private SafeExceptionLog() {}

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
