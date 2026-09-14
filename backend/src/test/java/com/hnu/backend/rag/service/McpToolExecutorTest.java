package com.hnu.backend.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.rag.model.McpToolCall;
import com.hnu.backend.rag.model.McpToolDefinition;
import com.hnu.backend.rag.model.ToolObservation;
import com.hnu.backend.rag.port.McpToolGateway;
import com.hnu.backend.rag.support.McpInputSchemaValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class McpToolExecutorTest {
  private final List<McpToolExecutor> executors = new ArrayList<>();

  @AfterEach
  void tearDown() {
    executors.forEach(McpToolExecutor::close);
  }

  @Test
  void rejectsDisabledUnknownWriteAndInvalidCallsWithoutInvokingGateway() {
    RagProperties config = configured();
    McpToolGateway gateway = gateway(tool("calendar.read", true), tool("calendar.write", false));
    McpToolExecutor executor = executor(gateway, config);
    McpToolCall read = new McpToolCall("calendar.read", Map.of("date", "2026-09-14"));

    config.getPipeline().getMcp().setEnabled(false);
    assertEquals(ToolObservation.Status.REJECTED, executor.execute(read).status());

    config.getPipeline().getMcp().setEnabled(true);
    config.getPipeline().getMcp().setAllowList(List.of("calendar.write"));
    assertEquals(ToolObservation.Status.REJECTED, executor.execute(read).status());
    assertEquals(
        "TOOL_NOT_READ_ONLY",
        executor
            .execute(new McpToolCall("calendar.write", Map.of("date", "2026-09-14")))
            .reasonCode());

    config.getPipeline().getMcp().setAllowList(List.of("calendar.read", "calendar.write"));
    assertEquals(
        "INVALID_ARGUMENTS",
        executor
            .execute(new McpToolCall("calendar.read", Map.of("unexpected", true)))
            .reasonCode());

    verify(gateway, never()).invoke(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void masksNestedSensitiveFieldsAndMarksTruncatedOutput() {
    RagProperties config = configured();
    config.getPipeline().getMcp().setMaxOutputChars(1000);
    McpToolGateway gateway = gateway(tool("calendar.read", true));
    McpToolCall call = new McpToolCall("calendar.read", Map.of("date", "2026-09-14"));
    when(gateway.invoke(call))
        .thenReturn(
            Map.of(
                "event", "评审",
                "token", "never-log-this",
                "nested", List.of(Map.of("employeeToken", "also-secret"))));
    McpToolExecutor executor = executor(gateway, config);

    ToolObservation result = executor.execute(call);

    assertEquals(ToolObservation.Status.SUCCESS, result.status());
    assertTrue(result.content().contains("[REDACTED]"));
    assertFalse(result.content().contains("never-log-this"));
    assertFalse(result.content().contains("also-secret"));
    assertFalse(result.truncated());

    config.getPipeline().getMcp().setMaxOutputChars(12);
    ToolObservation truncated = executor.execute(call);
    assertTrue(truncated.truncated());
    assertTrue(truncated.content().length() <= 12);
  }

  @Test
  void timesOutSlowGateway() {
    RagProperties config = configured();
    config.getPipeline().getMcp().setTimeoutMs(10);
    McpToolGateway gateway = gateway(tool("calendar.read", true));
    McpToolCall call = new McpToolCall("calendar.read", Map.of("date", "2026-09-14"));
    when(gateway.invoke(call))
        .thenAnswer(
            ignored -> {
              Thread.sleep(1000);
              return Map.of("event", "评审");
            });
    McpToolExecutor executor = executor(gateway, config);

    ToolObservation result = executor.execute(call);

    assertEquals(ToolObservation.Status.TIMEOUT, result.status());
    assertEquals("TOOL_TIMEOUT", result.reasonCode());
  }

  @Test
  void rejectsDuplicateToolsAndUnsupportedSchemasAtRegistration() {
    RagProperties config = configured();
    McpToolDefinition duplicate = tool("calendar.read", true);
    McpToolGateway duplicateGateway = gateway(duplicate, duplicate);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new McpToolRegistry(List.of(duplicateGateway), config, new McpInputSchemaValidator()));

    McpToolDefinition openSchema =
        new McpToolDefinition(
            "unsafe.read",
            "不安全工具",
            true,
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", true),
            Set.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new McpToolRegistry(
                List.of(gateway(openSchema)), config, new McpInputSchemaValidator()));
  }

  private RagProperties configured() {
    RagProperties config = new RagProperties();
    config.getPipeline().getMcp().setEnabled(true);
    config.getPipeline().getMcp().setAllowList(List.of("calendar.read", "calendar.write"));
    return config;
  }

  private McpToolExecutor executor(McpToolGateway gateway, RagProperties config) {
    McpToolRegistry registry =
        new McpToolRegistry(List.of(gateway), config, new McpInputSchemaValidator());
    McpToolExecutor executor = new McpToolExecutor(registry, config);
    executors.add(executor);
    return executor;
  }

  private McpToolGateway gateway(McpToolDefinition... tools) {
    McpToolGateway gateway = mock(McpToolGateway.class);
    when(gateway.tools()).thenReturn(List.of(tools));
    return gateway;
  }

  private McpToolDefinition tool(String name, boolean readOnly) {
    return new McpToolDefinition(
        name,
        "读取日历",
        readOnly,
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("date", Map.of("type", "string", "minLength", 1)),
            "required",
            List.of("date"),
            "additionalProperties",
            false),
        Set.of("employeeToken"));
  }
}
