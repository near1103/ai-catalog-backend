package com.nearapp.aicatalogbackend.config;

import com.nearapp.aicatalogbackend.security.FirebaseTokenFilter;
import com.nearapp.aicatalogbackend.security.FirebaseTokenVerifier;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${app.cors.allowed-origins:}") String origins) {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(
        Arrays.stream(origins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList());
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, FirebaseTokenVerifier verifier) throws Exception {
    return http.cors(Customizer.withDefaults())
        .csrf(c -> c.disable())
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            c ->
                c.requestMatchers(HttpMethod.GET, "/api/public/**", "/media/**")
                    .permitAll()
                    .requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            c ->
                c.authenticationEntryPoint(
                        (req, res, e) -> {
                          res.setStatus(401);
                          res.setContentType("application/problem+json");
                          res.getWriter()
                              .write("{\"status\":401,\"detail\":\"Authentication required\"}");
                        })
                    .accessDeniedHandler(
                        (req, res, e) -> {
                          res.setStatus(403);
                          res.setContentType("application/problem+json");
                          res.getWriter()
                              .write("{\"status\":403,\"detail\":\"ADMIN claim required\"}");
                        }))
        .addFilterBefore(
            new FirebaseTokenFilter(verifier), UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
