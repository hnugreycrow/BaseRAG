package com.hnu.backend.document.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.document.mapper.DocumentChunkMapper;
import com.hnu.backend.document.mapper.DocumentMapper;
import com.hnu.backend.document.mapper.DocumentVersionMapper;
import com.hnu.backend.document.storage.FileStorage;
import com.hnu.backend.shared.error.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentCleanupServiceTest {
  private final DocumentMapper documents = mock(DocumentMapper.class);
  private final DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
  private final DocumentChunkMapper chunks = mock(DocumentChunkMapper.class);
  private final FileStorage storage = mock(FileStorage.class);
  private final DocumentCleanupService service =
      new DocumentCleanupService(documents, versions, chunks, storage);

  @Test
  void processingVersionPreventsAllDeletionWrites() {
    when(versions.selectCount(any())).thenReturn(1L);
    assertEquals(
        "DOCUMENT_PROCESSING",
        assertThrows(ApiException.class, () -> service.deleteRecords(UUID.randomUUID())).code());
    verifyNoInteractions(documents, chunks, storage);
    verify(versions, never()).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
  }

  @Test
  void missingScopeIsRejectedBeforePersistence() {
    assertThrows(ApiException.class, () -> service.storageKeys(null));
    assertThrows(ApiException.class, () -> service.deleteRecords(null));
    verifyNoInteractions(documents, versions, chunks, storage);
  }

  @Test
  void storageFailureDoesNotStopRemainingFilesOrEscapeToCaller() {
    doThrow(new IllegalStateException("storage unavailable")).when(storage).remove("first");
    assertDoesNotThrow(() -> service.removeStoredFiles(List.of("first", "second")));
    var order = inOrder(storage);
    order.verify(storage).remove("first");
    order.verify(storage).remove("second");
  }
}
