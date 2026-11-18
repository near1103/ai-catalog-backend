package com.nearapp.aicatalogbackend.security;

import com.google.firebase.auth.FirebaseAuthException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class FirebaseTokenFilter extends OncePerRequestFilter {
  private final FirebaseTokenVerifier verifier;

  public FirebaseTokenFilter(FirebaseTokenVerifier verifier) {
    this.verifier = verifier;
  }

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null) {
      if (!header.startsWith("Bearer ") || header.substring(7).isBlank()) {
        unauthorized(response);
        return;
      }
      try {
        var identity = verifier.verify(header.substring(7));
        var roles =
            identity.admin()
                ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.<SimpleGrantedAuthority>of();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
            new UsernamePasswordAuthenticationToken(identity.uid(), null, roles));
        SecurityContextHolder.setContext(context);
      } catch (FirebaseAuthException | IllegalArgumentException e) {
        SecurityContextHolder.clearContext();
        unauthorized(response);
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private void unauthorized(HttpServletResponse response) throws IOException {
    response.setStatus(401);
    response.setContentType("application/problem+json");
    response.getWriter().write("{\"status\":401,\"detail\":\"Invalid Firebase ID token\"}");
  }
}
