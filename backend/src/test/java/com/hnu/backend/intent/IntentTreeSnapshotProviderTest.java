package com.hnu.backend.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.rag.mcp.McpToolDefinition;
import com.hnu.backend.rag.mcp.McpToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class IntentTreeSnapshotProviderTest {
  private final IntentTreeService intentTreeService = mock(IntentTreeService.class);
  private final McpToolRegistry tools = mock(McpToolRegistry.class);
  private final IntentTreeSnapshotProvider provider =
      new IntentTreeSnapshotProvider(intentTreeService, tools);

  @Test
  void cachesOneImmutableSnapshotUntilInvalidated() {
    IntentNode root = node(null, "业务系统", null, null, List.of(), true);
    IntentNode kb =
        node(root.id(), "制度", IntentNode.Kind.KB, null, List.of(UUID.randomUUID()), true);
    IntentNode mcp = node(root.id(), "日程", IntentNode.Kind.MCP, "calendar.read", List.of(), true);
    when(intentTreeService.list()).thenReturn(List.of(root, kb, mcp));
    when(tools.availableReadOnlyTools()).thenReturn(List.of(calendarTool()));

    IntentTreeSnapshot first = provider.snapshot();
    IntentTreeSnapshot second = provider.snapshot();

    assertSame(first, second);
    assertEquals("业务系统 > 制度", first.paths().get(kb.id()));
    assertEquals(
        List.of(kb.id(), mcp.id()), first.activeLeaves().stream().map(IntentNode::id).toList());
    assertEquals("calendar.read", first.promptLeaves().get(1).get("toolName"));
    assertThrows(UnsupportedOperationException.class, () -> first.nodes().add(root));
    verify(intentTreeService, times(1)).list();
    verify(tools, times(1)).availableReadOnlyTools();

    provider.invalidate(new IntentTreeChangedEvent());
    IntentTreeSnapshot rebuilt = provider.snapshot();

    assertNotSame(first, rebuilt);
    verify(intentTreeService, times(2)).list();
  }

  @Test
  void concurrentFirstReadersShareOneCompleteSnapshot() throws Exception {
    IntentNode leaf = node(null, "闲聊", IntentNode.Kind.SYSTEM, null, List.of(), true);
    when(intentTreeService.list())
        .thenAnswer(
            ignored -> {
              Thread.sleep(50);
              return List.of(leaf);
            });
    when(tools.availableReadOnlyTools()).thenReturn(List.of());
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Future<IntentTreeSnapshot>> futures = new ArrayList<>();
      for (int index = 0; index < 8; index++) {
        futures.add(executor.submit(provider::snapshot));
      }
      IntentTreeSnapshot expected = futures.getFirst().get();
      for (Future<IntentTreeSnapshot> future : futures) {
        assertSame(expected, future.get());
        assertEquals(leaf.id(), future.get().activeLeaves().getFirst().id());
      }
    }
    verify(intentTreeService, times(1)).list();
    verify(tools, times(1)).availableReadOnlyTools();
  }

  @Test
  void invalidationListenerRunsAfterCommitAndSupportsCommittedProgrammaticTransactions()
      throws Exception {
    TransactionalEventListener listener =
        IntentTreeSnapshotProvider.class
            .getMethod("invalidate", IntentTreeChangedEvent.class)
            .getAnnotation(TransactionalEventListener.class);

    assertEquals(TransactionPhase.AFTER_COMMIT, listener.phase());
    assertTrue(listener.fallbackExecution());
  }

  private IntentNode node(
      UUID parentId,
      String name,
      IntentNode.Kind kind,
      String toolName,
      List<UUID> knowledgeBaseIds,
      boolean enabled) {
    return new IntentNode(
        UUID.randomUUID(),
        parentId,
        name,
        name,
        List.of(name),
        kind,
        toolName,
        knowledgeBaseIds,
        enabled,
        0);
  }

  private McpToolDefinition calendarTool() {
    return new McpToolDefinition("calendar.read", "查询日程", true, Map.of("type", "object"), Set.of());
  }
}
