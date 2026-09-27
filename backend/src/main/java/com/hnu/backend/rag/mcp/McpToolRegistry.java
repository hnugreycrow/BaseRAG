package com.hnu.backend.rag.mcp;

import com.hnu.backend.rag.config.RagProperties;
import com.hnu.backend.rag.config.RagStageSettings;
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

  private final RagStageSettings.Tools config;
  private final McpInputSchemaValidator schemas;
  private final Map<String, RegisteredTool> tools;

  /**
   * 建立唯一的服务端工具索引；重复名称或无效 schema 会阻止启动。
   *
   * @param gateways 可登记工具的外部网关
   * @param config 当前工具开关与白名单
   * @param schemas 工具输入 schema 校验器
   */
  @org.springframework.beans.factory.annotation.Autowired
  public McpToolRegistry(
      List<McpToolGateway> gateways,
      RagStageSettings.Tools config,
      McpInputSchemaValidator schemas) {
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
    if (!config.enabled()) {
      return List.of();
    }
    Set<String> allowList = Set.copyOf(config.allowList());
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
    if (!config.enabled()) {
      return RoutingCheck.MCP_DISABLED;
    }
    RegisteredTool registered = tools.get(toolName);
    if (registered == null || !config.allowList().contains(toolName)) {
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

  /** 工具在服务端复核后的拒绝原因；仅 {@code ALLOWED} 可进入外部网关。 */
  public enum RoutingCheck {
    ALLOWED,
    MCP_DISABLED,
    TOOL_NOT_ALLOWED,
    TOOL_NOT_READ_ONLY,
    INVALID_ARGUMENTS
  }

  /**
   * 服务端工具定义与实际执行网关的绑定，不能由模型输出替换。
   *
   * @param definition 已校验的服务端工具定义
   * @param gateway 登记该工具的执行网关
   */
  public record RegisteredTool(McpToolDefinition definition, McpToolGateway gateway) {}

  /** 兼容独立测试的旧配置装配方式。 */
  public McpToolRegistry(
      List<McpToolGateway> gateways, RagProperties config, McpInputSchemaValidator schemas) {
    this(gateways, RagStageSettings.tools(config), schemas);
  }
}
