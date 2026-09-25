package com.hnu.backend.auth.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.hnu.backend.auth.service.AdminUserService;
import com.hnu.backend.auth.service.CurrentUserService;
import com.hnu.backend.document.controller.DocumentController;
import com.hnu.backend.intent.IntentTreeController;
import com.hnu.backend.knowledgebase.controller.KnowledgeBaseController;
import com.hnu.backend.model.controller.ModelSettingsController;
import com.hnu.backend.model.service.ModelSettingsService;
import com.hnu.backend.observability.controller.RagRunController;
import com.hnu.backend.rag.controller.RagEvaluationController;
import com.hnu.backend.rag.controller.RetrievalSettingsController;
import com.hnu.backend.rag.service.RetrievalSettingsService;
import com.hnu.backend.shared.error.GlobalExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 管理接口统一声明 Sa-Token 管理员角色校验。 */
class ManagementAuthorizationTest {
  private SaTokenDao previousDao;
  private StpInterface previousInterface;
  private SaTokenContext previousContext;

  @BeforeEach
  void setUp() {
    previousDao = SaManager.getSaTokenDao();
    previousInterface = SaManager.getStpInterface();
    previousContext = SaManager.getSaTokenContext();
    SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
    SaManager.setStpInterface(
        new StpInterface() {
          @Override
          public List<String> getPermissionList(Object loginId, String loginType) {
            return List.of();
          }

          @Override
          public List<String> getRoleList(Object loginId, String loginType) {
            return List.of("USER");
          }
        });
    SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
  }

  @AfterEach
  void tearDown() {
    SaTokenContextMockUtil.clearContext();
    SaManager.setSaTokenContext(previousContext);
    SaManager.setStpInterface(previousInterface);
    SaManager.setSaTokenDao(previousDao);
  }

  @Test
  void protectsEveryManagementControllerWithAdminRole() {
    List<Class<?>> controllers =
        List.of(
            KnowledgeBaseController.class,
            DocumentController.class,
            RagRunController.class,
            RagEvaluationController.class,
            AdminUserController.class,
            IntentTreeController.class,
            ModelSettingsController.class,
            RetrievalSettingsController.class);

    for (Class<?> controller : controllers) {
      SaCheckRole annotation = controller.getAnnotation(SaCheckRole.class);
      assertNotNull(annotation, controller.getName());
      assertArrayEquals(new String[] {"ADMIN"}, annotation.value(), controller.getName());
    }
  }

  @Test
  void rejectsRegularUserBeforeCallingManagementService() throws Exception {
    CurrentUserService currentUserService = mock(CurrentUserService.class);
    AdminUserService adminUserService = mock(AdminUserService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new AdminUserController(currentUserService, adminUserService))
            .addInterceptors(new SaInterceptor())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    SaTokenContextMockUtil.setMockContext();
    StpUtil.login(UUID.randomUUID().toString());
    mvc.perform(get("/api/admin/users")).andExpect(status().isForbidden());

    verifyNoInteractions(currentUserService, adminUserService);
  }

  @Test
  void rejectsRegularUserBeforeReadingModelSettings() throws Exception {
    ModelSettingsService service = mock(ModelSettingsService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new ModelSettingsController(service))
            .addInterceptors(new SaInterceptor())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    SaTokenContextMockUtil.setMockContext();
    StpUtil.login(UUID.randomUUID().toString());
    mvc.perform(get("/api/admin/settings/models")).andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void rejectsRegularUserBeforeReadingRetrievalSettings() throws Exception {
    RetrievalSettingsService service = mock(RetrievalSettingsService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new RetrievalSettingsController(service))
            .addInterceptors(new SaInterceptor())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    SaTokenContextMockUtil.setMockContext();
    StpUtil.login(UUID.randomUUID().toString());
    mvc.perform(get("/api/admin/settings/retrieval")).andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }
}
