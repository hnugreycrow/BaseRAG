package com.hnu.backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.hnu.backend.shared.error.ApiException;
import org.junit.jupiter.api.Test;

class CsrfTokenServiceTest {
  @Test
  void issuesNonceIntoCurrentTokenSessionAndValidatesExactValue() {
    SaSession session = mock(SaSession.class);
    CsrfTokenService service = new CsrfTokenService();

    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getTokenSession).thenReturn(session);
      String nonce = service.issue();
      when(session.get("baserag.csrf")).thenReturn(nonce);

      service.requireValid(nonce);

      assertEquals(43, nonce.length());
      verify(session).set(eq("baserag.csrf"), anyString());
    }
  }

  @Test
  void rejectsMissingOrMismatchedNonce() {
    SaSession session = mock(SaSession.class);
    when(session.get("baserag.csrf")).thenReturn("expected");
    CsrfTokenService service = new CsrfTokenService();

    try (var stp = mockStatic(StpUtil.class)) {
      stp.when(StpUtil::getTokenSession).thenReturn(session);
      assertEquals(
          "CSRF_INVALID",
          assertThrows(ApiException.class, () -> service.requireValid(null)).code());
      assertEquals(
          "CSRF_INVALID",
          assertThrows(ApiException.class, () -> service.requireValid("wrong")).code());
    }
  }
}
