package com.hnu.backend.observability;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.shared.error.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TraceReasonCatalogTest {
  @Test
  void coversEveryDeclaredReasonAndSharedErrorWithoutBlankLabels() {
    var codes = new HashSet<String>();
    for (var reason : TraceReasonCatalog.values()) {
      assertTrue(codes.add(reason.code()));
      assertTrue(TraceReasonCatalog.contains(reason.code()));
      assertFalse(TraceReasonCatalog.label(null, null, reason.code()).isBlank());
    }
    for (var error : ErrorCode.values()) {
      assertTrue(TraceReasonCatalog.contains(error.code()));
      assertNotEquals("暂无说明", TraceReasonCatalog.label(null, null, error.code()));
    }
  }

  @Test
  void describesRecoveryOnlyForDegradedStageAndPreservesUnknownHistory() {
    assertEquals(
        "模型请求超时，使用原始问题继续",
        TraceReasonCatalog.label(
            RagStageName.QUERY_PLANNING, RagStageStatus.DEGRADED, "MODEL_TIMEOUT"));
    assertEquals(
        "模型请求超时，保留现有会话记忆继续",
        TraceReasonCatalog.label(
            RagStageName.MEMORY_SUMMARY, RagStageStatus.DEGRADED, "MODEL_TIMEOUT"));
    assertEquals(
        "模型请求超时",
        TraceReasonCatalog.label(
            RagStageName.ANSWER_MODEL, RagStageStatus.FAILED, "MODEL_TIMEOUT"));
    assertEquals(
        "暂无说明",
        TraceReasonCatalog.label(
            RagStageName.QUERY_PLANNING, RagStageStatus.DEGRADED, "LEGACY_UNKNOWN"));
    assertNull(TraceReasonCatalog.label(null, null, null));
    assertNull(TraceReasonCatalog.label(null, null, " "));
  }

  @Test
  void directTraceReasonLiteralsMustBeRegistered() throws Exception {
    // 防止新增直接采集调用绕过目录；变量来源依靠枚举引用和业务用例约束。
    Pattern call =
        Pattern.compile(
            "(?:skipped|degraded|generationSkipped|addRouteFallback|skipVectorStages)"
                + "\\([^;]*?\"([A-Z][A-Z0-9_]+)\"",
            Pattern.DOTALL);
    try (var paths = Files.walk(Path.of("src/main/java/com/hnu/backend"))) {
      for (Path path : paths.filter(value -> value.toString().endsWith(".java")).toList()) {
        var matches = call.matcher(Files.readString(path));
        while (matches.find()) {
          assertTrue(
              TraceReasonCatalog.contains(matches.group(1)),
              () -> path + " 产生未注册原因：" + matches.group(1));
        }
      }
    }
  }
}
