package com.hnu.backend.rag.memory;

import java.util.Objects;

public record MemoryTurn(int turnIndex, String userContent, String assistantContent) {
  public MemoryTurn {
    if (turnIndex < 1) throw new IllegalArgumentException("turnIndex must be positive");
    userContent = Objects.requireNonNull(userContent, "userContent");
    assistantContent = Objects.requireNonNull(assistantContent, "assistantContent");
  }
}
