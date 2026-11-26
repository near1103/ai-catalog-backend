package com.nearapp.aicatalogbackend.service;

import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.AiSuggestion;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.model.Product;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class GeminiCategorySuggestionService implements CategorySuggestionService {
  private static final Logger log = LoggerFactory.getLogger(GeminiCategorySuggestionService.class);
  public static final String SYSTEM_INSTRUCTION = CategoryPromptBuilder.SYSTEM_INSTRUCTION;

  private final ObjectProvider<ChatModel> models;
  private final CategoryPromptBuilder prompts;

  public GeminiCategorySuggestionService(ObjectProvider<ChatModel> models, ObjectMapper json) {
    this.models = models;
    this.prompts = new CategoryPromptBuilder(json);
  }

  public AiSuggestion suggest(Product product, List<Category> categories) {
    try {
      return requestProposal(product, categories);
    } catch (ApiException firstFailure) {
      if (!providerUnavailable(firstFailure)) throw firstFailure;
    }
    try {
      Thread.sleep(1000);
      return requestProposal(product, categories);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (ApiException failure) {
      if (!providerUnavailable(failure)) throw failure;
      throw unavailable();
    }
  }

  private AiSuggestion requestProposal(Product product, List<Category> categories) {
    try {
      var suggestion = request(product, categories, CategoryPromptBuilder.PROPOSAL_INSTRUCTION, CategorySuggestionParser.PROPOSAL_OUTPUT,
          answer -> CategorySuggestionParser.toSuggestion(answer, categories)).suggestion();
      if (CategorySuggestionParser.validSuggestion(suggestion, categories)) return suggestion;
    } catch (InvalidResponse invalidResponse) {
      log.warn("Gemini categorization returned incomplete or malformed JSON; asking for a corrected answer");
    }

    var corrected = request(product, categories, CategoryPromptBuilder.CORRECTION_INSTRUCTION,
        CategorySuggestionParser.PROPOSAL_OUTPUT, answer -> CategorySuggestionParser.toSuggestion(answer, categories)).suggestion();
    if (!CategorySuggestionParser.validSuggestion(corrected, categories))
      throw ApiException.external("Gemini could not propose a valid path in the current category tree");
    return corrected;
  }

  private static ApiException unavailable() {
    return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
        "Gemini is temporarily unavailable. Try again shortly or select a category manually");
  }

  private static boolean providerUnavailable(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ProviderStatus status && status.code() == 503) return true;
    }
    return false;
  }

  public SuggestionResponse suggestWithMetadata(Product product, List<Category> categories) {
    return request(product, categories, SYSTEM_INSTRUCTION,
        new BeanOutputConverter<>(CategorySuggestionParser.Answer.class), CategorySuggestionParser::toSuggestion);
  }

  private <T> SuggestionResponse request(Product product, List<Category> categories, String instruction,
      BeanOutputConverter<T> output, Function<T, AiSuggestion> convert) {
    ChatModel model = models.getIfAvailable();
    if (model == null)
      throw ApiException.external(
          "Gemini disabled. Configure GEMINI_API_KEY and APP_AI_MODEL=google-genai or select a"
              + " category manually");
    try {
      var prompt = ChatClient.create(model).prompt().system(instruction).user(prompts.input(product, categories));
      if (output == CategorySuggestionParser.PROPOSAL_OUTPUT && model instanceof GoogleGenAiChatModel) {
        prompt.options(GoogleGenAiChatOptions.builder()
            .responseMimeType("application/json").maxOutputTokens(4096));
      }
      var response = prompt.call().responseEntity(output);
      var answer = response.entity();
      if (answer == null) throw new IllegalArgumentException();
      var metadata = response.response().getMetadata();
      var usage = metadata.getUsage();
      return new SuggestionResponse(convert.apply(answer), metadata.getModel(),
          usage == null ? null : reportedTokens(usage.getPromptTokens()),
          usage == null ? null : reportedTokens(usage.getCompletionTokens()),
          usage == null ? null : reportedTokens(usage.getTotalTokens()));
    } catch (Exception e) {
      if (output == CategorySuggestionParser.PROPOSAL_OUTPUT && e instanceof tools.jackson.core.JacksonException)
        throw new InvalidResponse();
      var safeCause = diagnostic(e);
      if (providerUnavailable(safeCause)) {
        log.warn("Gemini is temporarily unavailable (HTTP 503)");
      } else {
        log.error("Gemini suggestion failed; exception types and stack traces follow", safeCause);
      }
      var failure = ApiException.external(
          "Gemini suggestion failed; manual categorization remains available");
      failure.initCause(safeCause);
      throw failure;
    }
  }

  private static final class InvalidResponse extends ApiException {
    InvalidResponse() {
      super(HttpStatus.BAD_GATEWAY, "Gemini returned incomplete or malformed category JSON");
    }
  }

  private static Integer reportedTokens(Integer value) {
    return value != null && value > 0 ? value : null;
  }

  public static final class ProviderStatus extends RuntimeException {
    private final int code;

    ProviderStatus(String type, int code) {
      super(type + " (HTTP " + code + ")");
      this.code = code;
    }

    public int code() {
      return code;
    }
  }

  private static Throwable diagnostic(Throwable failure) {
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    RuntimeException first = null;
    RuntimeException previous = null;
    while (failure != null && seen.add(failure)) {
      String type = failure.getClass().getName();
      RuntimeException safe = failure instanceof com.google.genai.errors.ApiException api
          ? new ProviderStatus(type, api.code()) : new RuntimeException(type);
      safe.setStackTrace(failure.getStackTrace());
      if (first == null) first = safe;
      else previous.initCause(safe);
      previous = safe;
      failure = failure.getCause();
    }
    return first;
  }
}
