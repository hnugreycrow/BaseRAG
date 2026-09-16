package com.hnu.backend.auth.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hnu.backend.auth.service.AdminUserService;
import com.hnu.backend.auth.service.CurrentUserService;
import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.document.controller.DocumentController;
import com.hnu.backend.document.service.DocumentService;
import com.hnu.backend.intent.IntentTreeController;
import com.hnu.backend.intent.IntentTreeService;
import com.hnu.backend.knowledgebase.controller.KnowledgeBaseController;
import com.hnu.backend.knowledgebase.service.KnowledgeBaseService;
import com.hnu.backend.observability.controller.RagRunController;
import com.hnu.backend.observability.service.RagRunQueryService;
import com.hnu.backend.rag.controller.RagEvaluationController;
import com.hnu.backend.rag.mcp.McpToolRegistry;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.GlobalExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 管理接口由后端逐项拒绝普通用户。 */
class ManagementAuthorizationTest {
  @Test
  void rejectsManagementEndpointsForRegularUser() throws Exception {
    CurrentUserService users = mock(CurrentUserService.class);
    when(users.requireAdmin())
        .thenThrow(new ApiException("FORBIDDEN", "当前账号无权执行此操作", HttpStatus.FORBIDDEN));
    KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
    DocumentService documents = mock(DocumentService.class);
    RagRunQueryService runs = mock(RagRunQueryService.class);
    AdminUserService adminUsers = mock(AdminUserService.class);
    IntentTreeService intentTree =
        mock(IntentTreeService.class, withSettings().mockMaker("mock-maker-subclass"));
    McpToolRegistry tools = mock(McpToolRegistry.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new KnowledgeBaseController(knowledgeBases, users),
                new DocumentController(documents, users, knowledgeBases),
                new RagRunController(users, runs),
                new RagEvaluationController(new RagProperties(), users),
                new AdminUserController(users, adminUsers),
                new IntentTreeController(intentTree, users, tools))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    UUID kb = UUID.randomUUID();
    UUID document = UUID.randomUUID();
    mvc.perform(get("/api/knowledge-bases")).andExpect(status().isForbidden());
    mvc.perform(
            post("/api/knowledge-bases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"公共库\",\"embeddingModelId\":\"model\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/api/knowledge-bases/{id}", kb)).andExpect(status().isForbidden());
    mvc.perform(
            post("/api/knowledge-bases/{id}/documents/chunk-jobs", kb)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"" + document + "\"]}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/knowledge-bases/{id}", kb)).andExpect(status().isForbidden());
    mvc.perform(get("/api/knowledge-bases/embedding-models")).andExpect(status().isForbidden());
    mvc.perform(get("/api/knowledge-bases/{id}/documents", kb)).andExpect(status().isForbidden());
    mvc.perform(get("/api/knowledge-bases/{id}/documents/{document}", kb, document))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/observability/rag-runs")).andExpect(status().isForbidden());
    mvc.perform(get("/api/evaluation/config")).andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/users")).andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/intent-nodes")).andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/intent-nodes/tools")).andExpect(status().isForbidden());
    mvc.perform(delete("/api/admin/intent-nodes/{id}", UUID.randomUUID()))
        .andExpect(status().isForbidden());
    verifyNoInteractions(knowledgeBases, documents, runs, adminUsers, intentTree, tools);
  }
}
