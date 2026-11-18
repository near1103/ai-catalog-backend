package com.nearapp.aicatalogbackend.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.springframework.stereotype.Component;

@Component
public class FirebaseTokenVerifier {
  public record Identity(String uid, boolean admin) {}

  private final FirebaseAuth auth;

  public FirebaseTokenVerifier(FirebaseAuth auth) {
    this.auth = auth;
  }

  public Identity verify(String token) throws FirebaseAuthException {
    var verified = auth.verifyIdToken(token, true);
    return new Identity(verified.getUid(), Boolean.TRUE.equals(verified.getClaims().get("admin")));
  }
}
