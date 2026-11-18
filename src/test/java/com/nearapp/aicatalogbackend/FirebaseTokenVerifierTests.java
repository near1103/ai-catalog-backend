package com.nearapp.aicatalogbackend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.nearapp.aicatalogbackend.security.FirebaseTokenVerifier;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FirebaseTokenVerifierTests {
  @Test
  void booleanAdminClaimGrantsRole() throws Exception {
    var auth = mock(FirebaseAuth.class);
    var token = mock(FirebaseToken.class);
    when(auth.verifyIdToken("token", true)).thenReturn(token);
    when(token.getUid()).thenReturn("firebase-uid");
    when(token.getClaims()).thenReturn(Map.of("admin", true));
    var identity = new FirebaseTokenVerifier(auth).verify("token");
    assertTrue(identity.admin());
    assertEquals("firebase-uid", identity.uid());
    verify(auth).verifyIdToken("token", true);
  }

  @Test
  void stringAndUnrelatedRoleDoNotGrantAdmin() throws Exception {
    var auth = mock(FirebaseAuth.class);
    var token = mock(FirebaseToken.class);
    when(auth.verifyIdToken("token", true)).thenReturn(token);
    when(token.getClaims()).thenReturn(Map.of("admin", "true", "role", "ADMIN"));
    assertFalse(new FirebaseTokenVerifier(auth).verify("token").admin());
  }

  @Test
  void absentClaimDoesNotGrantAdmin() throws Exception {
    var auth = mock(FirebaseAuth.class);
    var token = mock(FirebaseToken.class);
    when(auth.verifyIdToken("token", true)).thenReturn(token);
    when(token.getClaims()).thenReturn(Map.of());
    assertFalse(new FirebaseTokenVerifier(auth).verify("token").admin());
  }
}
