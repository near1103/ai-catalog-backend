package com.nearapp.aicatalogbackend.config;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "google-genai")
public class GeminiConfig {
  public static final int TIMEOUT_MS = 60_000;
  @Bean(destroyMethod = "close")
  Client googleGenAiClient(GoogleGenAiConnectionProperties properties) {
    String key = properties.getApiKey();
    if (key == null || key.isBlank())
      throw new IllegalStateException(
          "Gemini requires GEMINI_API_KEY when APP_AI_MODEL=google-genai");
    return Client.builder()
        .apiKey(key)
        .httpOptions(
            HttpOptions.builder()
                .timeout(TIMEOUT_MS)
                .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                .build())
        .build();
  }
}
