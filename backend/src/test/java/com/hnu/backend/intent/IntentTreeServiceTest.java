package com.hnu.backend.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.knowledgebase.mapper.KnowledgeBaseMapper;
import com.hnu.backend.rag.mcp.McpToolRegistry;
import com.hnu.backend.shared.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IntentTreeServiceTest {
  private final IntentNodeMapper nodes = mock(IntentNodeMapper.class);
  private final IntentBindingMapper bindings = mock(IntentBindingMapper.class);
  private final KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
  private final McpToolRegistry tools = mock(McpToolRegistry.class);
  private final IntentTreeService service =
      new IntentTreeService(nodes, bindings, knowledgeBases, tools);

  @Test
  void refusesThirtyThirdEnabledLeaf() {
    List<IntentNodeEntity> existing = new ArrayList<>();
    for (int index = 0; index < 32; index++) existing.add(systemEntity());
    when(nodes.selectList(null)).thenReturn(existing);
    when(bindings.selectList(null)).thenReturn(List.of());
    IntentNodeRequest request =
        new IntentNodeRequest(
            null, "另一个系统直答", "", List.of(), IntentNode.Kind.SYSTEM, null, List.of(), true, 0);

    ApiException error = assertThrows(ApiException.class, () -> service.create(request));

    assertEquals("INTENT_LEAF_LIMIT", error.code());
    verify(nodes, never()).insert(any(IntentNodeEntity.class));
  }

  @Test
  void filtersDisabledAndInvalidBindingsBeforeClassification() {
    IntentNodeEntity validSystem = systemEntity();
    IntentNodeEntity disabledSystem = systemEntity();
    disabledSystem.setEnabled(false);
    IntentNodeEntity invalidKb = systemEntity();
    invalidKb.setKind("KB");
    UUID kbId = UUID.randomUUID();
    IntentBindingEntity binding = new IntentBindingEntity();
    binding.setId(UUID.randomUUID());
    binding.setNodeId(invalidKb.getId());
    binding.setKnowledgeBaseId(kbId);
    when(nodes.selectList(null)).thenReturn(List.of(validSystem, disabledSystem, invalidKb));
    when(bindings.selectList(null)).thenReturn(List.of(binding));
    when(tools.availableReadOnlyTools()).thenReturn(List.of());

    List<IntentNode> active = service.activeLeaves();

    assertEquals(1, active.size());
    assertEquals(validSystem.getId(), active.getFirst().id());
    assertTrue(active.stream().noneMatch(node -> node.id().equals(invalidKb.getId())));
  }

  @Test
  void refusesFourthLevel() {
    IntentNodeEntity root = systemEntity();
    root.setKind(null);
    IntentNodeEntity child = systemEntity();
    child.setKind(null);
    child.setParentId(root.getId());
    IntentNodeEntity grandchild = systemEntity();
    grandchild.setKind(null);
    grandchild.setParentId(child.getId());
    when(nodes.selectList(null)).thenReturn(List.of(root, child, grandchild));
    when(bindings.selectList(null)).thenReturn(List.of());

    IntentNodeRequest request =
        new IntentNodeRequest(
            grandchild.getId(),
            "第四级",
            "",
            List.of(),
            IntentNode.Kind.SYSTEM,
            null,
            List.of(),
            true,
            0);

    ApiException error = assertThrows(ApiException.class, () -> service.create(request));
    assertEquals("INTENT_DEPTH_EXCEEDED", error.code());
    verify(nodes, never()).insert(any(IntentNodeEntity.class));
  }

  @Test
  void refusesCycleWhenMovingParent() {
    IntentNodeEntity root = systemEntity();
    root.setKind(null);
    IntentNodeEntity child = systemEntity();
    child.setKind(null);
    child.setParentId(root.getId());
    when(nodes.selectList(null)).thenReturn(List.of(root, child));
    when(bindings.selectList(null)).thenReturn(List.of());

    IntentNodeRequest request =
        new IntentNodeRequest(child.getId(), "父节点", "", List.of(), null, null, List.of(), true, 0);

    ApiException error =
        assertThrows(ApiException.class, () -> service.update(root.getId(), request));
    assertEquals("INTENT_CYCLE", error.code());
    verify(nodes, never()).updateById(any(IntentNodeEntity.class));
  }

  private IntentNodeEntity systemEntity() {
    IntentNodeEntity entity = new IntentNodeEntity();
    entity.setId(UUID.randomUUID());
    entity.setName("系统直答");
    entity.setDescription("");
    entity.setExamplesJson("[]");
    entity.setKind("SYSTEM");
    entity.setEnabled(true);
    entity.setSortOrder(0);
    return entity;
  }
}
