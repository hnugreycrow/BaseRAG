package com.hnu.backend.rag.mcp;

import com.hnu.backend.configuration.RagProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 以服务端注册信息为唯一事实来源管理可自动调用的 MCP 只读工具。 */
@Component
public class McpToolRegistry {
  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");

  private final RagProperties config;
  private final McpInputSchemaValidator schemas;
  private final Map<String, RegisteredTool> tools;

  public McpToolRegistry(
      List<McpToolGateway> gateways, RagProperties config, McpInputSchemaValidator schemas) {
    this.config = config;
    this.schemas = schemas;
    Map<String, RegisteredTool> indexed = new LinkedHashMap<>();
    for (McpToolGateway gateway : gateways) {
      for (McpToolDefinition definition : gateway.tools()) {
        validateDefinition(definition);
        if (indexed.put(definition.name(), new RegisteredTool(definition, gateway)) != null) {
          throw new IllegalArgumentException(
              "Duplicate MCP tool registration: " + definition.name());
        }
      }
    }
    this.tools = Map.copyOf(indexed);
  }

  /** 返回当前特性开关和白名单共同允许暴露给分类模型的只读工具。 */
  public List<McpToolDefinition> availableReadOnlyTools() {
    if (!config.getPipeline().getMcp().isEnabled()) {
      return List.of();
    }
    Set<String> allowList = Set.copyOf(config.getPipeline().getMcp().getAllowList());
    List<McpToolDefinition> available = new ArrayList<>();
    tools.values().stream()
        .map(RegisteredTool::definition)
        .filter(McpToolDefinition::readOnly)
        .filter(definition -> allowList.contains(definition.name()))
        .forEach(available::add);
    return List.copyOf(available);
  }

  /** 对模型建议的工具和参数执行服务端安全检查。 */
  public RoutingCheck check(String toolName, Map<String, Object> arguments) {
    if (!config.getPipeline().getMcp().isEnabled()) {
      return RoutingCheck.MCP_DISABLED;
    }
    RegisteredTool registered = tools.get(toolName);
    if (registered == null || !config.getPipeline().getMcp().getAllowList().contains(toolName)) {
      return RoutingCheck.TOOL_NOT_ALLOWED;
    }
    if (!registered.definition().readOnly()) {
      return RoutingCheck.TOOL_NOT_READ_ONLY;
    }
    if (!schemas.accepts(registered.definition().inputSchema(), arguments)) {
      return RoutingCheck.INVALID_ARGUMENTS;
    }
    return RoutingCheck.ALLOWED;
  }

  /** 返回已通过全部安全检查的网关绑定，供执行器在调用前二次解析。 */
  public Optional<RegisteredTool> resolve(McpToolCall call) {
    return check(call.toolName(), call.arguments()) == RoutingCheck.ALLOWED
        ? Optional.of(tools.get(call.toolName()))
        : Optional.empty();
  }

  private void validateDefinition(McpToolDefinition definition) {
    if (!SAFE_NAME.matcher(definition.name()).matches()) {
      throw new IllegalArgumentException("Invalid MCP tool name: " + definition.name());
    }
    if (definition.sensitiveFields().stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException("MCP sensitive field names must not be blank");
    }
    schemas.validateSchema(definition.inputSchema());
  }

  public enum RoutingCheck {
    ALLOWED,
    MCP_DISABLED,
    TOOL_NOT_ALLOWED,
    TOOL_NOT_READ_ONLY,
    INVALID_ARGUMENTS
  }

  public record RegisteredTool(McpToolDefinition definition, McpToolGateway gateway) {}
}
