package com.hnu.backend.rag.port;

import com.hnu.backend.rag.model.RagMemory;
import java.util.UUID;

public interface MemoryProvider {
  RagMemory load(UUID conversationId, int beforeTurn);
}
