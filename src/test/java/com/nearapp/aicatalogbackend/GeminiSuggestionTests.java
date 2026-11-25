package com.nearapp.aicatalogbackend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.model.AiSuggestion;
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
                            "{\"leafCategoryId\":\"leaf\",\"reason\":\"A"
                                + " phone\"}")))));
    var now = Instant.now();
    var product =
        new Product(
            "p",
            "Phone",
            "Description",
            Map.of("color", "black"),
            List.of(new ProductImage("https://private-image", "private-key", true)),
            "hidden-reference-leaf",
            ProductStatus.DRAFT,
            new com.nearapp.aicatalogbackend.model.AiSuggestion("old-root", "old-section", "old-leaf", "old-reason", now),
            com.nearapp.aicatalogbackend.model.CategorySource.AI,
            now,
            now);
    var result =
        new GeminiCategorySuggestionService(provider, new ObjectMapper())
            .suggest(product, categoryTree());
    assertEquals("leaf", result.leafCategoryId());
    assertEquals("section", result.sectionCategoryId());
    assertEquals("root", result.rootCategoryId());
    assertNotNull(result.generatedAt());
    var request = ArgumentCaptor.forClass(Prompt.class);
    verify(model, times(1)).call(request.capture());
    String text = request.getValue().getContents();
    assertTrue(text.contains("Phone"));
    assertTrue(text.contains("black"));
    assertTrue(text.contains("root"));
    assertFalse(text.contains("private-image"));
    assertFalse(text.contains("private-key"));
    assertFalse(text.contains("hidden-reference-leaf"));
    assertFalse(text.contains("old-leaf"));
    assertFalse(text.contains("categorySource"));
    assertFalse(text.contains("aiSuggestion"));
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

  @Test
  @SuppressWarnings("unchecked")
  void proposesPathForEmptyTreeWithServerGeneratedIds() {
    ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
    ChatModel model = mock(ChatModel.class);
    when(provider.getIfAvailable()).thenReturn(model);
    when(model.getOptions()).thenReturn(ChatOptions.builder().build());
    when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
        new AssistantMessage("""
            {"type":"NEW_CATEGORY","reason":"Empty tree","parentId":null,
             "names":[" Electronics ","Networking","Mesh systems"]}
            """)))));
    var service = new GeminiCategorySuggestionService(provider, new ObjectMapper());
    var first = service.suggest(product(), List.of());
    var second = service.suggest(product(), List.of());
    assertEquals(AiSuggestion.Type.NEW_CATEGORY, first.type());
    assertNull(first.proposal().parentId());
    assertEquals(List.of("Electronics", "Networking", "Mesh systems"), first.proposal().names());
    assertNotEquals(first.proposal().id(), second.proposal().id());
    var request = ArgumentCaptor.forClass(Prompt.class);
    verify(model, times(2)).call(request.capture());
    var prompt = request.getValue().getContents();
    assertTrue(prompt.contains("\"oneOf\""));
    assertTrue(prompt.contains("\"items\": {\"type\": \"string\""));
  }

  @Test
  @SuppressWarnings("unchecked")
  void retriesTemporaryProviderFailureOnce() {
    ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
    ChatModel model = mock(ChatModel.class);
    when(provider.getIfAvailable()).thenReturn(model);
    when(model.getOptions()).thenReturn(ChatOptions.builder().build());
    var unavailable = new RuntimeException(
        new com.google.genai.errors.ServerException(503, "UNAVAILABLE", "Provider overloaded"));
    when(model.call(any(Prompt.class))).thenThrow(unavailable).thenReturn(
        new ChatResponse(List.of(new Generation(new AssistantMessage("""
            {"rootCategoryId":"root","sectionCategoryId":"section","leafCategoryId":"leaf","reason":"Fit"}
            """)))));
    var service = new GeminiCategorySuggestionService(provider, new ObjectMapper());
    assertEquals("leaf", service.suggest(product(), categoryTree()).leafCategoryId());
    verify(model, times(2)).call(any(Prompt.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void repeatedProviderFailureReturnsServiceUnavailable() {
    ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
    ChatModel model = mock(ChatModel.class);
    when(provider.getIfAvailable()).thenReturn(model);
    when(model.getOptions()).thenReturn(ChatOptions.builder().build());
    when(model.call(any(Prompt.class))).thenThrow(new RuntimeException(
        new com.google.genai.errors.ServerException(503, "UNAVAILABLE", "Provider overloaded")));
    var service = new GeminiCategorySuggestionService(provider, new ObjectMapper());
    assertEquals(503, assertThrows(ApiException.class,
        () -> service.suggest(product(), List.of())).status().value());
    verify(model, times(2)).call(any(Prompt.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void correctsInventedLeafWithProposalUnderExistingGroup() {
    ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
    ChatModel model = mock(ChatModel.class);
    when(provider.getIfAvailable()).thenReturn(model);
    when(model.getOptions()).thenReturn(ChatOptions.builder().build());
    when(model.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("""
            {"rootCategoryId":"root","sectionCategoryId":"section","leafCategoryId":"invented","reason":"Router"}
            """)))))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("""
            {"type":"NEW_CATEGORY","parentId":"  SECTION  ","names":["Networking","Routers"],"reason":"No router leaf"}
            """)))));
    var service = new GeminiCategorySuggestionService(provider, new ObjectMapper());
    var suggestion = service.suggest(product(), categoryTree());
    assertEquals(AiSuggestion.Type.NEW_CATEGORY, suggestion.type());
    assertEquals("section", suggestion.proposal().parentId());
    assertEquals(List.of("Routers"), suggestion.proposal().names());
    verify(model, times(2)).call(any(Prompt.class));
  }

  private List<Category> categoryTree() {
    return List.of(new Category("root", "Electronics", null, 1, null),
        new Category("section", "Computers", "root", 2, null),
        new Category("leaf", "Laptops", "section", 3, null));
  }
}
