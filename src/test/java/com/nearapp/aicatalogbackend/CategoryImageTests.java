package com.nearapp.aicatalogbackend;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.cloudinary.Cloudinary;
import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.repository.CategoryRepository;
import com.nearapp.aicatalogbackend.repository.ProductRepository;
import com.nearapp.aicatalogbackend.service.CategoryService;
import com.nearapp.aicatalogbackend.storage.CloudinaryFileStorageService;
import com.nearapp.aicatalogbackend.storage.FileStorageService;
import com.nearapp.aicatalogbackend.storage.ImageValidator;
import com.nearapp.aicatalogbackend.storage.LocalFileStorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

class CategoryImageTests {
  CategoryRepository repo;
  ProductRepository products;
  FileStorageService storage;
  CategoryService service;
  final byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

  @BeforeEach
  void setup() {
    repo = mock(CategoryRepository.class);
    products = mock(ProductRepository.class);
    storage = mock(FileStorageService.class);
    service = new CategoryService(repo, products, storage);
    when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
  }

  MockMultipartFile file() {
    return new MockMultipartFile("file", "image.png", "image/png", png);
  }

  Category current() {
    var category = new Category("c", "Category", null, 1, "old-url", "old-key");
    when(repo.findById("c")).thenReturn(Optional.of(category));
    return category;
  }

  @Test
  void uploadsAtAllLevelsAndPreservesCategoryFields() {
    for (int level = 1; level <= 3; level++) {
      var old = new Category("c", "Name", level == 1 ? null : "parent", level, null);
      when(repo.findById("c")).thenReturn(Optional.of(old));
      when(storage.uploadCategory(eq("c"), any())).thenReturn(new FileStorageService.StoredFile("new-url", "new-key"));
      var saved = service.uploadImage("c", file());
      assertEquals(old.name(), saved.name());
      assertEquals(old.parentId(), saved.parentId());
      assertEquals(level, saved.level());
      assertEquals("new-url", saved.imageUrl());
      assertEquals("new-key", saved.imageStorageKey());
    }
  }

  @Test
  void replacementDeletesPreviousManagedImageAfterSave() {
    current();
    when(storage.uploadCategory(eq("c"), any())).thenReturn(new FileStorageService.StoredFile("new-url", "new-key"));
    service.uploadImage("c", file());
    var order = inOrder(repo, storage);
    order.verify(repo).findById("c");
    order.verify(storage).uploadCategory(eq("c"), any());
    order.verify(repo).save(any());
    order.verify(storage).delete("old-key");
  }

  @Test
  void failedSaveRollsBackOnlyTheNewUpload() {
    current();
    when(storage.uploadCategory(eq("c"), any())).thenReturn(new FileStorageService.StoredFile("new-url", "new-key"));
    var failure = new IllegalStateException("Database failure");
    when(repo.save(any())).thenThrow(failure);
    assertSame(failure, assertThrows(IllegalStateException.class, () -> service.uploadImage("c", file())));
    verify(storage).delete("new-key");
    verify(storage, never()).delete("old-key");
  }

  @Test
  void failedUploadLeavesCurrentCategoryUntouched() {
    current();
    when(storage.uploadCategory(eq("c"), any())).thenThrow(ApiException.bad("Invalid image"));
    assertThrows(ApiException.class, () -> service.uploadImage("c", file()));
    verify(repo, never()).save(any());
    verify(storage, never()).delete(any());
  }

  @Test
  void missingCategoryDoesNotUploadAFile() {
    when(repo.findById("missing")).thenReturn(Optional.empty());
    assertThrows(ApiException.class, () -> service.uploadImage("missing", file()));
    verifyNoInteractions(storage);
  }

  @Test
  void regularSavePreservesManagedKeyForUnchangedUrl() {
    current();
    var saved = service.save("c", new Category(null, "Renamed", null, 1, "old-url"));
    assertEquals("old-key", saved.imageStorageKey());
    verifyNoInteractions(storage);
  }

  @Test
  void clearingImageCleansUpManagedAssetAndRetainsOtherFields() {
    current();
    var saved = service.save("c", new Category(null, "Category", null, 1, null));
    assertNull(saved.imageUrl());
    assertNull(saved.imageStorageKey());
    assertEquals("Category", saved.name());
    verify(storage).delete("old-key");
  }

  @Test
  void deletingReferencedCategoryRetainsImage() {
    current();
    when(repo.findAll()).thenReturn(List.of());
    when(products.existsByCategoryId("c")).thenReturn(true);
    assertThrows(ApiException.class, () -> service.delete("c"));
    verifyNoInteractions(storage);
  }

  @Test
  void deletingCategoryCleansUpItsManagedAsset() {
    current();
    when(repo.findAll()).thenReturn(List.of());
    service.delete("c");
    var order = inOrder(repo, storage);
    order.verify(repo).delete("c");
    order.verify(storage).delete("old-key");
  }

  @Test
  void internalStorageKeyIsNotPartOfTheJsonContract() throws Exception {
    var mapper = new ObjectMapper();
    var json = mapper.writeValueAsString(new Category("c", "Category", null, 1, "url", "private-key"));
    assertFalse(json.contains("imageStorageKey"));
    assertFalse(json.contains("private-key"));
    var category = mapper.readValue("{\"id\":\"c\",\"name\":\"Category\",\"level\":1,\"imageStorageKey\":\"injected\"}", Category.class);
    assertNull(category.imageStorageKey());
  }

  @Test
  void localCategoryImageUsesExistingValidationAndCanBeDeleted(@TempDir Path directory) throws Exception {
    var local = new LocalFileStorageService(directory.toString(), "http://localhost/media", new ImageValidator(100));
    var uploaded = local.uploadCategory("c", file());
    assertArrayEquals(png, Files.readAllBytes(directory.resolve(uploaded.storageKey())));
    assertTrue(uploaded.url().startsWith("http://localhost/media/"));
    local.delete(uploaded.storageKey());
    assertFalse(Files.exists(directory.resolve(uploaded.storageKey())));
    assertThrows(ApiException.class, () -> local.uploadCategory("c", new MockMultipartFile("file", "bad.png", "image/png", "not png".getBytes())));
  }

  @Test
  void cloudinaryCategoryImagesUseSeparateNamespace() throws Exception {
    try (var clients = mockConstruction(Cloudinary.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS))) {
      var cloud = new CloudinaryFileStorageService("test", "placeholder", "placeholder", new ImageValidator(100));
      var uploader = clients.constructed().getFirst().uploader();
      when(uploader.upload(any(byte[].class), anyMap())).thenReturn(Map.of("secure_url", "url", "public_id", "ai-catalog/categories/c/key"));
      assertEquals("url", cloud.uploadCategory("c", file()).url());
      verify(uploader).upload(any(byte[].class), argThat(options -> options.get("public_id").toString().startsWith("ai-catalog/categories/c/")));
      when(uploader.destroy(anyString(), anyMap())).thenReturn(Map.of("result", "ok"));
      assertDoesNotThrow(() -> cloud.delete("ai-catalog/categories/c/key"));
    }
  }
}
