package com.hnu.backend.rag.memory;

import java.util.UUID;

public interface MemoryProvider {
  RagMemory load(UUID conversationId, int beforeTurn);
}
