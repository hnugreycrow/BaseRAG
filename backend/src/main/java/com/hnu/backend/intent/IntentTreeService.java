package com.hnu.backend.intent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hnu.backend.knowledgebase.mapper.KnowledgeBaseMapper;
import com.hnu.backend.rag.mcp.McpToolRegistry;
import com.hnu.backend.shared.error.ApiException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 管理所有管理员共享的意图树，持久化只通过 MyBatis-Plus Mapper。 */
@Service
public class IntentTreeService {
  private static final int MAX_ACTIVE_LEAVES = 32;
  private final IntentNodeMapper nodes;
  private final IntentBindingMapper bindings;
  private final KnowledgeBaseMapper knowledgeBases;
  private final McpToolRegistry tools;
  private final JsonMapper json = JsonMapper.builder().build();

  public IntentTreeService(
      IntentNodeMapper nodes,
      IntentBindingMapper bindings,
      KnowledgeBaseMapper knowledgeBases,
      McpToolRegistry tools) {
    this.nodes = nodes;
    this.bindings = bindings;
    this.knowledgeBases = knowledgeBases;
    this.tools = tools;
  }

  /** 返回全局树的平面节点列表，前端使用 parentId 构建层级。 */
  @Transactional(readOnly = true)
  public List<IntentNode> list() {
    Map<UUID, List<UUID>> linked = new HashMap<>();
    bindings
        .selectList(null)
        .forEach(
            binding ->
                linked
                    .computeIfAbsent(binding.getNodeId(), ignored -> new ArrayList<>())
                    .add(binding.getKnowledgeBaseId()));
    return nodes.selectList(null).stream()
        .map(entity -> fromEntity(entity, linked.getOrDefault(entity.getId(), List.of())))
        .sorted(
            Comparator.comparingInt(IntentNode::sortOrder)
                .thenComparing(IntentNode::name)
                .thenComparing(IntentNode::id))
        .toList();
  }

  /** 返回当前可执行的启用叶子；配置失效的绑定不会交给模型。 */
  public List<IntentNode> activeLeaves() {
    List<IntentNode> all = list();
    Map<UUID, IntentNode> byId = index(all);
    Set<UUID> parents = new HashSet<>();
    all.forEach(
        node -> {
          if (node.parentId() != null) parents.add(node.parentId());
        });
    Set<String> toolNames = new HashSet<>();
    tools.availableReadOnlyTools().forEach(tool -> toolNames.add(tool.name()));
    return all.stream()
        .filter(node -> node.kind() != null && !parents.contains(node.id()))
        .filter(node -> enabledPath(node, byId))
        .filter(
            node ->
                switch (node.kind()) {
                  case KB ->
                      !node.knowledgeBaseIds().isEmpty()
                          && node.knowledgeBaseIds().stream()
                              .allMatch(id -> knowledgeBases.findAdminOwned(id) != null);
                  case MCP -> node.toolName() != null && toolNames.contains(node.toolName());
                  case SYSTEM -> true;
                })
        .toList();
  }

  /** 新建分类或可执行叶子。 */
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public IntentNode create(IntentNodeRequest request) {
    IntentNode node = normalized(UUID.randomUUID(), request);
    List<IntentNode> all = new ArrayList<>(list());
    all.add(node);
    validate(node, all);
    nodes.insert(toEntity(node));
    saveBindings(node);
    return node;
  }

  /** 修改层级、绑定或启停状态，校验调整后的整棵树。 */
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public IntentNode update(UUID id, IntentNodeRequest request) {
    List<IntentNode> all = new ArrayList<>(list());
    if (all.stream().noneMatch(node -> node.id().equals(id))) {
      throw ApiException.notFound("INTENT_NODE_NOT_FOUND", "意图节点不存在");
    }
    IntentNode replacement = normalized(id, request);
    all.removeIf(node -> node.id().equals(id));
    all.add(replacement);
    validate(replacement, all);
    nodes.updateById(toEntity(replacement));
    bindings.delete(
        new LambdaQueryWrapper<IntentBindingEntity>().eq(IntentBindingEntity::getNodeId, id));
    saveBindings(replacement);
    return replacement;
  }

  /** 删除无子节点的节点；绑定记录由数据库外键级联删除。 */
  @Transactional
  public void delete(UUID id) {
    List<IntentNode> all = list();
    if (all.stream().noneMatch(node -> node.id().equals(id))) {
      throw ApiException.notFound("INTENT_NODE_NOT_FOUND", "意图节点不存在");
    }
    if (all.stream().anyMatch(node -> id.equals(node.parentId()))) {
      throw ApiException.conflict("INTENT_NODE_HAS_CHILDREN", "请先删除子节点");
    }
    nodes.deleteById(id);
  }

  private IntentNode normalized(UUID id, IntentNodeRequest request) {
    if (request == null
        || request.name() == null
        || request.name().isBlank()
        || request.name().strip().length() > 100
        || request.sortOrder() < 0) {
      throw ApiException.bad("INVALID_INTENT_NODE", "节点名称或排序无效");
    }
    String description = request.description() == null ? "" : request.description().strip();
    if (description.length() > 300) {
      throw ApiException.bad("INVALID_INTENT_NODE", "节点描述过长");
    }
    List<String> examples =
        request.examples() == null
            ? List.of()
            : request.examples().stream().map(item -> item == null ? "" : item.strip()).toList();
    if (examples.size() > 4
        || examples.stream().anyMatch(item -> item.isBlank() || item.length() > 120)) {
      throw ApiException.bad("INVALID_INTENT_NODE", "示例问题无效");
    }
    List<UUID> kbIds =
        request.knowledgeBaseIds() == null
            ? List.of()
            : request.knowledgeBaseIds().stream().distinct().toList();
    String toolName = request.toolName() == null ? null : request.toolName().strip();
    return new IntentNode(
        id,
        request.parentId(),
        request.name().strip(),
        description,
        examples,
        request.kind(),
        toolName == null || toolName.isBlank() ? null : toolName,
        kbIds,
        request.enabled(),
        request.sortOrder());
  }

  private void validate(IntentNode node, List<IntentNode> all) {
    Map<UUID, IntentNode> byId = index(all);
    if (node.parentId() != null && !byId.containsKey(node.parentId())) {
      throw ApiException.bad("INVALID_INTENT_PARENT", "父节点不存在");
    }
    for (IntentNode candidate : all) {
      Set<UUID> visited = new HashSet<>();
      IntentNode current = candidate;
      int depth = 0;
      while (current != null) {
        if (!visited.add(current.id())) {
          throw ApiException.bad("INTENT_CYCLE", "意图树不能成环");
        }
        if (++depth > 3) {
          throw ApiException.bad("INTENT_DEPTH_EXCEEDED", "意图树最多三级");
        }
        current = current.parentId() == null ? null : byId.get(current.parentId());
      }
      if (candidate.kind() != null
          && all.stream().anyMatch(child -> candidate.id().equals(child.parentId()))) {
        throw ApiException.bad("INVALID_INTENT_KIND", "有子节点的节点不能设置执行类型");
      }
    }
    long active =
        all.stream()
            .filter(candidate -> candidate.kind() != null && enabledPath(candidate, byId))
            .count();
    if (active > MAX_ACTIVE_LEAVES) {
      throw ApiException.bad("INTENT_LEAF_LIMIT", "启用的叶子节点最多 32 个");
    }
    if (node.kind() == IntentNode.Kind.KB) {
      if (node.knowledgeBaseIds().isEmpty() || node.toolName() != null) {
        throw ApiException.bad("INVALID_INTENT_BINDING", "知识库叶子必须绑定公共知识库");
      }
      for (UUID kbId : node.knowledgeBaseIds()) {
        if (knowledgeBases.findAdminOwned(kbId) == null) {
          throw ApiException.bad("INVALID_INTENT_BINDING", "绑定的公共知识库不存在");
        }
      }
    } else if (node.kind() == IntentNode.Kind.MCP) {
      if (!node.knowledgeBaseIds().isEmpty()
          || node.toolName() == null
          || tools.availableReadOnlyTools().stream()
              .noneMatch(tool -> tool.name().equals(node.toolName()))) {
        throw ApiException.bad("INVALID_INTENT_BINDING", "只能绑定当前可用的只读工具");
      }
    } else if (!node.knowledgeBaseIds().isEmpty() || node.toolName() != null) {
      throw ApiException.bad("INVALID_INTENT_BINDING", "该节点不能绑定知识库或工具");
    }
  }

  private void saveBindings(IntentNode node) {
    node.knowledgeBaseIds()
        .forEach(
            kbId -> {
              IntentBindingEntity binding = new IntentBindingEntity();
              binding.setId(UUID.randomUUID());
              binding.setNodeId(node.id());
              binding.setKnowledgeBaseId(kbId);
              bindings.insert(binding);
            });
  }

  private boolean enabledPath(IntentNode node, Map<UUID, IntentNode> byId) {
    IntentNode current = node;
    while (current != null) {
      if (!current.enabled()) return false;
      current = current.parentId() == null ? null : byId.get(current.parentId());
    }
    return true;
  }

  private Map<UUID, IntentNode> index(List<IntentNode> all) {
    Map<UUID, IntentNode> result = new HashMap<>();
    all.forEach(node -> result.put(node.id(), node));
    return result;
  }

  private IntentNodeEntity toEntity(IntentNode node) {
    IntentNodeEntity entity = new IntentNodeEntity();
    entity.setId(node.id());
    entity.setParentId(node.parentId());
    entity.setName(node.name());
    entity.setDescription(node.description());
    entity.setExamplesJson(json.writeValueAsString(node.examples()));
    entity.setKind(node.kind() == null ? null : node.kind().name());
    entity.setToolName(node.toolName());
    entity.setEnabled(node.enabled());
    entity.setSortOrder(node.sortOrder());
    return entity;
  }

  private IntentNode fromEntity(IntentNodeEntity entity, List<UUID> kbIds) {
    JsonNode parsed = json.readTree(entity.getExamplesJson());
    List<String> examples = new ArrayList<>();
    parsed.forEach(item -> examples.add(item.asString()));
    return new IntentNode(
        entity.getId(),
        entity.getParentId(),
        entity.getName(),
        entity.getDescription(),
        List.copyOf(examples),
        entity.getKind() == null ? null : IntentNode.Kind.valueOf(entity.getKind()),
        entity.getToolName(),
        List.copyOf(kbIds),
        entity.getEnabled(),
        entity.getSortOrder());
  }
}
