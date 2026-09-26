package com.hnu.backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hnu.backend.common.exception.ApiException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AccountPolicyTest {
  private final AccountPolicy policy = new AccountPolicy();

  @Test
  void normalizesUsernameAndAcceptsOnlyDocumentedAsciiAlphabet() {
    assertEquals("alice.name-1", policy.username("  Alice.Name-1  "));
    assertThrows(ApiException.class, () -> policy.username("ab"));
    assertThrows(ApiException.class, () -> policy.username("用户"));
    assertThrows(ApiException.class, () -> policy.username("__legacy_owner__"));
  }

  @Test
  void enforcesBcryptByteBoundaryWithoutCompositionRules() {
    assertEquals("abcdefghijkl", policy.password("abcdefghijkl"));
    assertThrows(ApiException.class, () -> policy.password("short"));
    assertEquals(72, "密".repeat(24).getBytes(StandardCharsets.UTF_8).length);
    assertEquals("密".repeat(24), policy.password("密".repeat(24)));
    assertThrows(ApiException.class, () -> policy.password("密".repeat(25)));
  }

  @Test
  void bcryptHashesAndVerifiesValidatedPassword() {
    var encoder = new BCryptPasswordEncoder(4);
    String password = policy.password("plainwords12");
    String hash = encoder.encode(password);

    assertTrue(hash.startsWith("$2"));
    assertTrue(encoder.matches(password, hash));
  }
}
