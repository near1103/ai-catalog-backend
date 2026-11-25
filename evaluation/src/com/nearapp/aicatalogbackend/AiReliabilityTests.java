package com.nearapp.aicatalogbackend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.*;
import com.nearapp.aicatalogbackend.repository.CategoryRepository;
import com.nearapp.aicatalogbackend.repository.ProductRepository;
import com.nearapp.aicatalogbackend.service.*;
import com.nearapp.aicatalogbackend.storage.FileStorageService;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AiReliabilityTests {
  private Product original;
  private ProductRepository products;
  private CategorySuggestionService ai;
  private ProductService service;

  @BeforeEach
  void setup() {
    var tree = Map.of(
        "root", new Category("root", "Electronics", null, 1, null),
        "otherRoot", new Category("otherRoot", "Home", null, 1, null),
        "section", new Category("section", "Mobile", "root", 2, null),
        "leaf", new Category("leaf", "Phones", "section", 3, null));
    var categories = mock(CategoryRepository.class);
    when(categories.findById(any())).thenAnswer(call -> Optional.ofNullable(tree.get(call.getArgument(0))));
    when(categories.findAll()).thenReturn(List.copyOf(tree.values()));
    products = mock(ProductRepository.class);
    ai = mock(CategorySuggestionService.class);
    var storage = mock(FileStorageService.class);
    service = new ProductService(products, new CategoryService(categories, products, storage), storage, ai);
    var now = Instant.now();
    original = new Product("p", "Phone", "Description", Map.of("color", "black"),
        List.of(), null, ProductStatus.DRAFT, null, null, now, now);
    when(products.findById("p")).thenReturn(Optional.of(original));
    when(products.save(any())).thenAnswer(call -> call.getArgument(0));
  }

  static Stream<Arguments> invalidSuggestions() {
    return Stream.of(
        Arguments.of("unknown leaf", "root", "section", "missing", "Phone"),
        Arguments.of("wrong levels", "section", "root", "leaf", "Phone"),
        Arguments.of("disconnected parent", "otherRoot", "section", "leaf", "Phone"),
        Arguments.of("blank reason", "root", "section", "leaf", "  "),
        Arguments.of("missing reason", "root", "section", "leaf", null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidSuggestions")
  void invalidProviderOutputAllowsManualRecovery(String scenario, String root, String section,
      String leaf, String reason) {
    when(ai.suggest(any(), any())).thenReturn(new AiSuggestion(root, section, leaf, reason, Instant.now()));
    assertUnchangedAfterFailure();
  }

  @Test
  void unavailableProviderKeepsAllProductFieldsAndAllowsManualRecovery() {
    when(ai.suggest(any(), any())).thenThrow(ApiException.external("Unavailable"));
    assertUnchangedAfterFailure();
  }

  private void assertUnchangedAfterFailure() {
    assertEquals(502, assertThrows(ApiException.class, () -> service.suggest("p")).status().value());
    verify(products, never()).save(any());
    assertEquals(original, service.get("p"));
    var assigned = service.category("p", "leaf");
    assertEquals("leaf", assigned.categoryId());
    assertEquals(CategorySource.MANUAL, assigned.categorySource());
    assertNull(assigned.aiSuggestion());
    assertEquals(original.description(), assigned.description());
    assertEquals(original.characteristics(), assigned.characteristics());
  }
}
