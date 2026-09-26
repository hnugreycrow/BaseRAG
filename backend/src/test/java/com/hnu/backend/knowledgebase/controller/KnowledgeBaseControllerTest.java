package com.hnu.backend.knowledgebase.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hnu.backend.application.KnowledgeBaseDeletionService;
import com.hnu.backend.auth.service.CurrentUserService;
import com.hnu.backend.knowledgebase.service.KnowledgeBaseService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class KnowledgeBaseControllerTest {
  @Test
  void deleteKeepsExistingRouteAndDelegatesToApplicationService() throws Exception {
    KnowledgeBaseDeletionService deletion = mock(KnowledgeBaseDeletionService.class);
    KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new KnowledgeBaseController(
                    knowledgeBases, deletion, mock(CurrentUserService.class)))
            .build();
    UUID id = UUID.randomUUID();

    mvc.perform(delete("/api/knowledge-bases/{id}", id)).andExpect(status().isNoContent());

    verify(deletion).delete(id);
    verifyNoInteractions(knowledgeBases);
  }
}
