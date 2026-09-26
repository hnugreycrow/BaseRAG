package com.hnu.backend.intent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.intent.dto.IntentNodeRequest;
import com.hnu.backend.intent.entity.IntentNodeEntity;
import com.hnu.backend.intent.event.IntentTreeChangedEvent;
import com.hnu.backend.intent.mapper.IntentBindingMapper;
import com.hnu.backend.intent.mapper.IntentNodeMapper;
import com.hnu.backend.intent.model.IntentNode;
import com.hnu.backend.knowledgebase.mapper.KnowledgeBaseMapper;
import com.hnu.backend.rag.mcp.McpToolRegistry;
import com.hnu.backend.shared.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class IntentTreeServiceTest {
  private final IntentNodeMapper intentNodeMapper = mock(IntentNodeMapper.class);
  private final IntentBindingMapper intentBindingMapper = mock(IntentBindingMapper.class);
  private final KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
  private final McpToolRegistry tools = mock(McpToolRegistry.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final IntentTreeService intentTreeService =
      new IntentTreeService(
          intentNodeMapper, intentBindingMapper, knowledgeBaseMapper, tools, events);

  @Test
  void refusesThirtyThirdEnabledLeaf() {
    List<IntentNodeEntity> existing = new ArrayList<>();
    for (int index = 0; index < 32; index++) {
      existing.add(systemEntity());
    }
    when(intentNodeMapper.selectList(null)).thenReturn(existing);
    when(intentBindingMapper.selectList(null)).thenReturn(List.of());
    IntentNodeRequest request =
        new IntentNodeRequest(
            null, "另一个系统直答", "", List.of(), IntentNode.Kind.SYSTEM, null, List.of(), true, 0);

    ApiException error = assertThrows(ApiException.class, () -> intentTreeService.create(request));

    assertEquals("INTENT_LEAF_LIMIT", error.code());
    verify(intentNodeMapper, never()).insert(any(IntentNodeEntity.class));
  }

  @Test
  void filtersDisabledAndMissingBindingsWithoutQueryingKnowledgeBases() {
    IntentNodeEntity validSystem = systemEntity();
    IntentNodeEntity disabledSystem = systemEntity();
    disabledSystem.setEnabled(false);
    IntentNodeEntity invalidKb = systemEntity();
    invalidKb.setKind("KB");
    when(intentNodeMapper.selectList(null))
        .thenReturn(List.of(validSystem, disabledSystem, invalidKb));
    when(intentBindingMapper.selectList(null)).thenReturn(List.of());
    when(tools.availableReadOnlyTools()).thenReturn(List.of());

    List<IntentNode> active = intentTreeService.activeLeaves();

    assertEquals(1, active.size());
    assertEquals(validSystem.getId(), active.getFirst().id());
    assertTrue(active.stream().noneMatch(node -> node.id().equals(invalidKb.getId())));
    verify(intentNodeMapper, times(1)).selectList(null);
    verify(intentBindingMapper, times(1)).selectList(null);
    verify(knowledgeBaseMapper, never()).findAdminOwned(any());
  }

  @Test
  void publishesInvalidationAfterCreate() {
    when(intentNodeMapper.selectList(null)).thenReturn(List.of());
    when(intentBindingMapper.selectList(null)).thenReturn(List.of());

    intentTreeService.create(
        new IntentNodeRequest(
            null, "系统直答", "", List.of(), IntentNode.Kind.SYSTEM, null, List.of(), true, 0));

    verify(events).publishEvent(any(IntentTreeChangedEvent.class));
  }

  @Test
  void publishesInvalidationAfterUpdate() {
    IntentNodeEntity existing = systemEntity();
    when(intentNodeMapper.selectList(null)).thenReturn(List.of(existing));
    when(intentBindingMapper.selectList(null)).thenReturn(List.of());

    intentTreeService.update(
        existing.getId(),
        new IntentNodeRequest(
            null, "新名称", "", List.of(), IntentNode.Kind.SYSTEM, null, List.of(), true, 0));

    verify(events).publishEvent(any(IntentTreeChangedEvent.class));
  }

  @Test
  void publishesInvalidationAfterDelete() {
    IntentNodeEntity existing = systemEntity();
    when(intentNodeMapper.selectList(null)).thenReturn(List.of(existing));
    when(intentBindingMapper.selectList(null)).thenReturn(List.of());

    intentTreeService.delete(existing.getId());

    verify(events).publishEvent(any(IntentTreeChangedEvent.class));
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
    when(intentNodeMapper.selectList(null)).thenReturn(List.of(root, child, grandchild));
    when(intentBindingMapper.selectList(null)).thenReturn(List.of());

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

    ApiException error = assertThrows(ApiException.class, () -> intentTreeService.create(request));
    assertEquals("INTENT_DEPTH_EXCEEDED", error.code());
    verify(intentNodeMapper, never()).insert(any(IntentNodeEntity.class));
  }

  @Test
  void refusesCycleWhenMovingParent() {
    IntentNodeEntity root = systemEntity();
    root.setKind(null);
    IntentNodeEntity child = systemEntity();
    child.setKind(null);
    child.setParentId(root.getId());
    when(intentNodeMapper.selectList(null)).thenReturn(List.of(root, child));
    when(intentBindingMapper.selectList(null)).thenReturn(List.of());

    IntentNodeRequest request =
        new IntentNodeRequest(child.getId(), "父节点", "", List.of(), null, null, List.of(), true, 0);

    ApiException error =
        assertThrows(ApiException.class, () -> intentTreeService.update(root.getId(), request));
    assertEquals("INTENT_CYCLE", error.code());
    verify(intentNodeMapper, never()).updateById(any(IntentNodeEntity.class));
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
