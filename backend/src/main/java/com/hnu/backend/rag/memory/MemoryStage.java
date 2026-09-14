package com.hnu.backend.rag.memory;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class MemoryStage {
  private final MemoryProvider provider;

  public MemoryStage(MemoryProvider provider) {
    this.provider = provider;
  }

  public RagMemory execute(UUID conversationId, int beforeTurn) {
    Objects.requireNonNull(conversationId, "conversationId");
    if (beforeTurn < 1) throw new IllegalArgumentException("beforeTurn must be positive");
    return Objects.requireNonNull(
        provider.load(conversationId, beforeTurn), "MemoryProvider returned null");
  }
}
