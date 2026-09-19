package com.hnu.backend.intent;

import com.hnu.backend.rag.mcp.McpToolDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 单次意图路由使用的不可变树快照。 */
public record IntentTreeSnapshot(
    List<IntentNode> nodes,
    List<IntentNode> activeLeaves,
    Map<UUID, IntentNode> activeLeavesById,
    Map<UUID, String> paths,
    List<Map<String, Object>> promptLeaves) {

  public IntentTreeSnapshot {
    nodes = List.copyOf(nodes);
    activeLeaves = List.copyOf(activeLeaves);
    activeLeavesById = Map.copyOf(activeLeavesById);
    paths = Map.copyOf(paths);
    promptLeaves = List.copyOf(promptLeaves);
  }

  /** 从一次数据库读取和一次工具注册表读取构建完整路由快照。 */
  public static IntentTreeSnapshot from(
      List<IntentNode> sourceNodes, List<McpToolDefinition> availableTools) {
    List<IntentNode> nodes = sourceNodes.stream().map(IntentTreeSnapshot::immutableNode).toList();
    Map<UUID, IntentNode> allById = index(nodes);
    Map<String, McpToolDefinition> toolsByName = new HashMap<>();
    availableTools.forEach(tool -> toolsByName.put(tool.name(), tool));
    Set<UUID> parents = new HashSet<>();
    nodes.forEach(
        node -> {
          if (node.parentId() != null) {
            parents.add(node.parentId());
          }
        });
    Map<UUID, String> paths = paths(nodes, allById);
    List<IntentNode> activeLeaves =
        nodes.stream()
            .filter(node -> node.kind() != null && !parents.contains(node.id()))
            .filter(node -> enabledPath(node, allById))
            .filter(node -> executable(node, toolsByName))
            .toList();
    Map<UUID, IntentNode> activeLeavesById = index(activeLeaves);
    List<Map<String, Object>> promptLeaves =
        activeLeaves.stream()
            .map(node -> promptLeaf(node, paths.get(node.id()), toolsByName))
            .toList();
    return new IntentTreeSnapshot(nodes, activeLeaves, activeLeavesById, paths, promptLeaves);
  }

  private static IntentNode immutableNode(IntentNode node) {
    return new IntentNode(
        node.id(),
        node.parentId(),
        node.name(),
        node.description(),
        List.copyOf(node.examples()),
        node.kind(),
        node.toolName(),
        List.copyOf(node.knowledgeBaseIds()),
        node.enabled(),
        node.sortOrder());
  }

  private static boolean executable(IntentNode node, Map<String, McpToolDefinition> toolsByName) {
    return switch (node.kind()) {
      case KB -> !node.knowledgeBaseIds().isEmpty();
      case MCP -> node.toolName() != null && toolsByName.containsKey(node.toolName());
      case SYSTEM -> true;
    };
  }

  private static boolean enabledPath(IntentNode node, Map<UUID, IntentNode> byId) {
    Set<UUID> visited = new HashSet<>();
    IntentNode current = node;
    while (current != null) {
      if (!visited.add(current.id()) || !current.enabled()) {
        return false;
      }
      current = current.parentId() == null ? null : byId.get(current.parentId());
    }
    return true;
  }

  private static Map<UUID, String> paths(List<IntentNode> nodes, Map<UUID, IntentNode> byId) {
    Map<UUID, String> result = new HashMap<>();
    for (IntentNode node : nodes) {
      List<String> names = new ArrayList<>();
      Set<UUID> visited = new HashSet<>();
      IntentNode current = node;
      while (current != null && visited.add(current.id())) {
        names.add(current.name());
        current = current.parentId() == null ? null : byId.get(current.parentId());
      }
      Collections.reverse(names);
      result.put(node.id(), String.join(" > ", names));
    }
    return Map.copyOf(result);
  }

  private static Map<String, Object> promptLeaf(
      IntentNode node, String path, Map<String, McpToolDefinition> toolsByName) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("id", node.id());
    value.put("path", path);
    value.put("type", node.kind());
    value.put("description", node.description());
    value.put("examples", node.examples());
    if (node.kind() == IntentNode.Kind.MCP) {
      McpToolDefinition tool = toolsByName.get(node.toolName());
      value.put("toolName", tool.name());
      value.put("toolDescription", tool.description());
      value.put("inputSchema", tool.inputSchema());
    }
    return Collections.unmodifiableMap(value);
  }

  private static Map<UUID, IntentNode> index(List<IntentNode> nodes) {
    Map<UUID, IntentNode> result = new HashMap<>();
    nodes.forEach(node -> result.put(node.id(), node));
    return Map.copyOf(result);
  }
}
