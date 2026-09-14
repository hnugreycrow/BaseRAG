package com.hnu.backend.rag.model;

import java.util.List;
import java.util.Objects;

public record RagMemory(
    String summary,
    int summaryRevision,
    List<MemoryTurn> unsummarizedTurns,
    List<MemoryTurn> recentTurns,
    int loadedThroughTurn) {
  public RagMemory {
    summary = Objects.requireNonNull(summary, "summary");
    if (summaryRevision < 0)
      throw new IllegalArgumentException("summaryRevision must not be negative");
    unsummarizedTurns = List.copyOf(unsummarizedTurns);
    recentTurns = List.copyOf(recentTurns);
    if (loadedThroughTurn < 0) {
      throw new IllegalArgumentException("loadedThroughTurn must not be negative");
    }
  }
}
