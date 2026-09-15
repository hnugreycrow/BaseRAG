package com.hnu.backend.rag.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryStageTest {
  private final MemoryProvider provider = mock(MemoryProvider.class);
  private final MemoryStage stage = new MemoryStage(provider);

  @Test
  void delegatesToMemoryProvider() {
    UUID conversationId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    RagMemory expected =
        new RagMemory("{}", 1, List.of(), List.of(new MemoryTurn(2, "question", "answer")), 2);
    when(provider.load(ownerId, conversationId, 3)).thenReturn(expected);

    RagMemory actual = stage.execute(ownerId, conversationId, 3);

    assertEquals(expected, actual);
    verify(provider).load(ownerId, conversationId, 3);
  }

  @Test
  void ragMemoryDefensivelyCopiesTurnLists() {
    List<MemoryTurn> recent = new ArrayList<>();
    recent.add(new MemoryTurn(1, "question", "answer"));

    RagMemory memory = new RagMemory("{}", 0, List.of(), recent, 1);
    recent.clear();

    assertEquals(1, memory.recentTurns().size());
    assertThrows(
        UnsupportedOperationException.class,
        () -> memory.recentTurns().add(new MemoryTurn(2, "question", "answer")));
  }

  @Test
  void acceptsEmptyHistory() {
    UUID conversationId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    RagMemory empty = new RagMemory("{}", 0, List.of(), List.of(), 0);
    when(provider.load(ownerId, conversationId, 1)).thenReturn(empty);

    assertEquals(empty, stage.execute(ownerId, conversationId, 1));
  }
}
