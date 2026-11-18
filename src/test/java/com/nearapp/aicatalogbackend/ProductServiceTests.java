package com.nearapp.aicatalogbackend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nearapp.aicatalogbackend.dto.ProductInput;
import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.AiSuggestion;
import com.nearapp.aicatalogbackend.model.CategorySource;
import com.nearapp.aicatalogbackend.model.Product;
import com.nearapp.aicatalogbackend.model.ProductImage;
import com.nearapp.aicatalogbackend.model.ProductStatus;
import com.nearapp.aicatalogbackend.repository.ProductRepository;
import com.nearapp.aicatalogbackend.service.CategoryService;
import com.nearapp.aicatalogbackend.service.CategorySuggestionService;
import com.nearapp.aicatalogbackend.service.ProductService;
import com.nearapp.aicatalogbackend.storage.FileStorageService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class ProductServiceTests {
  ProductRepository repo;
  CategoryService categories;
  FileStorageService storage;
  CategorySuggestionService ai;
  ProductService service;
  Product current;

  @BeforeEach
  void setup() {
    repo = mock(ProductRepository.class);
    categories = mock(CategoryService.class);
    storage = mock(FileStorageService.class);
    ai = mock(CategorySuggestionService.class);
    service = new ProductService(repo, categories, storage, ai);
    current =
        new Product(
            "p",
            "Phone",
            "Description",
            Map.of("color", "black"),
            List.of(new ProductImage("https://external/image", null, true)),
            "leaf",
            ProductStatus.DRAFT,
            new AiSuggestion("root", "section", "leaf", "Reason", Instant.now()),
            CategorySource.MANUAL,
            Instant.now(),
            Instant.now());
    when(repo.findById("p")).thenAnswer(i -> Optional.of(current));
    when(repo.save(any()))
        .thenAnswer(
            i -> {
              current = i.getArgument(0);
              return current;
            });
  }

  void images(List<ProductImage> images, ProductStatus status) {
    current =
        new Product(
            current.id(),
            current.name(),
            current.description(),
            current.characteristics(),
            images,
            current.categoryId(),
            status,
            current.aiSuggestion(),
            current.categorySource(),
            current.createdAt(),
            current.updatedAt());
  }

  @Test
  void changedTextInvalidatesSuggestion() {
    assertNull(
        service
            .update("p", new ProductInput("Changed", "Description", current.characteristics()))
            .aiSuggestion());
  }

  @Test
  void unchangedTextKeepsSuggestion() {
    assertNotNull(
        service
            .update(
                "p",
                new ProductInput(current.name(), current.description(), current.characteristics()))
            .aiSuggestion());
  }

  @Test
  void descriptionInvalidatesSuggestion() {
    assertNull(
        service
            .update("p", new ProductInput(current.name(), "New", current.characteristics()))
            .aiSuggestion());
  }

  @Test
  void characteristicsInvalidateSuggestion() {
    assertNull(
        service
            .update(
                "p",
                new ProductInput(current.name(), current.description(), Map.of("color", "white")))
            .aiSuggestion());
  }

  @Test
  void firstUploadIsPrimaryAndKeepsSuggestion() {
    images(List.of(), ProductStatus.DRAFT);
    when(storage.upload(eq("p"), any()))
        .thenReturn(new FileStorageService.StoredFile("https://cloud/image", "key"));
    var result = service.upload("p", mock(MultipartFile.class));
    assertTrue(result.images().getFirst().isPrimary());
    assertNotNull(result.aiSuggestion());
  }

  @Test
  void onlyOnePrimary() {
    images(
        List.of(new ProductImage("one", null, true), new ProductImage("two", null, false)),
        ProductStatus.DRAFT);
    var p = service.primary("p", 1);
    assertFalse(p.images().get(0).isPrimary());
    assertTrue(p.images().get(1).isPrimary());
  }

  @Test
  void deletingPrimarySelectsFirstRemaining() {
    images(
        List.of(
            new ProductImage("one", null, true),
            new ProductImage("two", null, false),
            new ProductImage("three", null, false)),
        ProductStatus.DRAFT);
    var p = service.deleteImage("p", 0);
    assertTrue(p.images().getFirst().isPrimary());
    verifyNoInteractions(storage);
  }

  @Test
  void activeLastImageCannotBeRemoved() {
    images(current.images(), ProductStatus.ACTIVE);
    assertThrows(ApiException.class, () -> service.deleteImage("p", 0));
    verifyNoInteractions(storage);
  }

  @Test
  void publicationRequiresImages() {
    images(List.of(), ProductStatus.DRAFT);
    assertThrows(ApiException.class, () -> service.publish("p"));
  }

  @Test
  void publicationRequiresPrimary() {
    images(List.of(new ProductImage("url", null, false)), ProductStatus.DRAFT);
    assertThrows(ApiException.class, () -> service.publish("p"));
  }

  @Test
  void publicationRequiresCategory() {
    current =
        new Product(
            "p",
            "Phone",
            null,
            Map.of(),
            current.images(),
            null,
            ProductStatus.DRAFT,
            null,
            null,
            Instant.now(),
            Instant.now());
    assertThrows(ApiException.class, () -> service.publish("p"));
  }

  @Test
  void publicDraftHidden() {
    assertThrows(ApiException.class, () -> service.getPublic("p"));
  }

  @Test
  void activeProductVisible() {
    images(current.images(), ProductStatus.ACTIVE);
    assertEquals(current, service.getPublic("p"));
  }

  @Test
  void externalImagesAreNeverDeletedFromProvider() {
    service.delete("p");
    verifyNoInteractions(storage);
    verify(repo).delete("p");
  }

  @Test
  void providerFailureRetainsInactiveProductAndKeys() {
    images(List.of(new ProductImage("url", "managed", true)), ProductStatus.ACTIVE);
    doThrow(new IllegalStateException()).when(storage).delete("managed");
    assertThrows(ApiException.class, () -> service.delete("p"));
    assertEquals(ProductStatus.INACTIVE, current.status());
    assertEquals("managed", current.images().getFirst().storageKey());
    verify(repo, never()).delete(any());
  }

  @Test
  void imageFailureRetainsReferenceAndHidesProduct() {
    images(
        List.of(new ProductImage("one", "managed", true), new ProductImage("two", null, false)),
        ProductStatus.ACTIVE);
    doThrow(new IllegalStateException()).when(storage).delete("managed");
    assertThrows(ApiException.class, () -> service.deleteImage("p", 0));
    assertEquals(ProductStatus.INACTIVE, current.status());
    assertEquals(2, current.images().size());
  }

  @Test
  void manualCategoryWorksWhenAiFails() {
    when(ai.suggest(any(), any())).thenThrow(ApiException.external("Unavailable"));
    assertThrows(ApiException.class, () -> service.suggest("p"));
    assertEquals(CategorySource.MANUAL, service.category("p", "leaf").categorySource());
    assertEquals(ProductStatus.ACTIVE, service.publish("p").status());
  }

  @Test
  void validSuggestionIsNotAutomaticallyApplied() {
    var a = new AiSuggestion("root", "section", "other", "Reason", Instant.now());
    when(ai.suggest(any(), any())).thenReturn(a);
    var result = service.suggest("p");
    assertEquals("leaf", result.categoryId());
    assertEquals(a, result.aiSuggestion());
    verify(ai, times(1)).suggest(any(), any());
  }

  @Test
  void invalidAiPathIsNotSaved() {
    when(ai.suggest(any(), any())).thenReturn(current.aiSuggestion());
    doThrow(ApiException.bad("Invalid path")).when(categories).validatePath(any());
    assertThrows(ApiException.class, () -> service.suggest("p"));
    verify(repo, never()).save(any());
  }

  @Test
  void acceptSuggestionSetsAiSource() {
    assertEquals(CategorySource.AI, service.acceptSuggestion("p").categorySource());
  }

  @Test
  void uploadDatabaseFailureCompensatesAsset() {
    when(storage.upload(eq("p"), any()))
        .thenReturn(new FileStorageService.StoredFile("url", "key"));
    doThrow(new IllegalStateException()).when(repo).save(any());
    assertThrows(
        IllegalStateException.class,
        () -> service.upload("p", mock(MultipartFile.class)));
    verify(storage).delete("key");
  }
}
