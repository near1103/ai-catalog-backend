package com.nearapp.aicatalogbackend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.model.Product;
import com.nearapp.aicatalogbackend.model.ProductImage;
import com.nearapp.aicatalogbackend.model.ProductStatus;
import com.nearapp.aicatalogbackend.service.GeminiCategorySuggestionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

class GeminiSuggestionTests {
  @Test
  @SuppressWarnings("unchecked")
  void providerFailureKeepsContractAndLogsSafeCauseStack() {
    ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
    ChatModel model = mock(ChatModel.class);
    when(provider.getIfAvailable()).thenReturn(model);
    when(model.getOptions())
        .thenReturn(ChatOptions.builder().build());
    var cause = new IllegalArgumentException("secret-key and private product data");
    when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("private request", cause));
    var logger = (ch.qos.logback.classic.Logger)
        LoggerFactory.getLogger(GeminiCategorySuggestionService.class);
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var error = assertThrows(ApiException.class,
          () -> new GeminiCategorySuggestionService(provider, new ObjectMapper())
              .suggest(product(), List.of()));
      assertEquals(502, error.status().value());
      assertEquals("Gemini suggestion failed; manual categorization remains available", error.getMessage());
      var logged = appender.list.getFirst().getThrowableProxy();
      assertEquals(RuntimeException.class.getName(), logged.getMessage());
      assertEquals(IllegalArgumentException.class.getName(), logged.getCause().getMessage());
      assertEquals(cause.getStackTrace().length, logged.getCause().getStackTraceElementProxyArray().length);
      String trace = ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(logged);
      assertFalse(trace.contains("secret-key"));
      assertFalse(trace.contains("private request"));
      assertFalse(trace.contains("private product"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void malformedAnswerReturnsRecoverableError() {
    ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
    ChatModel model = mock(ChatModel.class);
    when(provider.getIfAvailable()).thenReturn(model);
    when(model.getOptions())
        .thenReturn(ChatOptions.builder().build());
    when(model.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("not JSON")))));
    assertEquals(502, assertThrows(ApiException.class,
        () -> new GeminiCategorySuggestionService(provider, new ObjectMapper())
            .suggest(product(), List.of())).status().value());
  }

  private Product product() {
    var now = Instant.now();
    return new Product("p", "Phone", null, Map.of(), List.of(), null,
        ProductStatus.DRAFT, null, null, now, now);
  }

  @Test
  @SuppressWarnings("unchecked")
  void sendsOnlyTextAndTreeInOneRequest() {
    ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
    ChatModel model = mock(ChatModel.class);
    when(provider.getIfAvailable()).thenReturn(model);
    when(model.getOptions())
        .thenReturn(ChatOptions.builder().build());
    when(model.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "{\"rootCategoryId\":\"root\",\"sectionCategoryId\":\"section\",\"leafCategoryId\":\"leaf\",\"reason\":\"A"
                                + " phone\"}")))));
    var now = Instant.now();
    var product =
        new Product(
            "p",
            "Phone",
            "Description",
            Map.of("color", "black"),
            List.of(new ProductImage("https://private-image", "private-key", true)),
            null,
            ProductStatus.DRAFT,
            null,
            null,
            now,
            now);
    var result =
        new GeminiCategorySuggestionService(provider, new ObjectMapper())
            .suggest(product, List.of(new Category("root", "Root", null, 1, null)));
    assertEquals("leaf", result.leafCategoryId());
    assertNotNull(result.generatedAt());
    var request = ArgumentCaptor.forClass(Prompt.class);
    verify(model, times(1)).call(request.capture());
    String text = request.getValue().getContents();
    assertTrue(text.contains("Phone"));
    assertTrue(text.contains("black"));
    assertTrue(text.contains("root"));
    assertFalse(text.contains("private-image"));
    assertFalse(text.contains("private-key"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void disabledAiReturnsRecoverableError() {
    ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
    assertThrows(
        ApiException.class,
        () ->
            new GeminiCategorySuggestionService(provider, new ObjectMapper())
                .suggest(null, List.of()));
  }
}
