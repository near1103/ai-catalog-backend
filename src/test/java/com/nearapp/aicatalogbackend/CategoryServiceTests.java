package com.nearapp.aicatalogbackend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.AiSuggestion;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.repository.CategoryRepository;
import com.nearapp.aicatalogbackend.repository.ProductRepository;
import com.nearapp.aicatalogbackend.service.CategoryService;
import com.nearapp.aicatalogbackend.storage.FileStorageService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CategoryServiceTests {
  CategoryRepository repo;
  ProductRepository products;
  CategoryService service;

  @BeforeEach
  void setup() {
    repo = mock(CategoryRepository.class);
    products = mock(ProductRepository.class);
    service = new CategoryService(repo, products, mock(FileStorageService.class));
    when(repo.findById("root"))
        .thenReturn(Optional.of(new Category("root", "Root", null, 1, null)));
    when(repo.findById("section"))
        .thenReturn(Optional.of(new Category("section", "Section", "root", 2, null)));
    when(repo.findById("leaf"))
        .thenReturn(Optional.of(new Category("leaf", "Leaf", "section", 3, null)));
  }

  @Test
  void validPath() {
    assertDoesNotThrow(
        () ->
            service.validatePath(
                new AiSuggestion("root", "section", "leaf", "Reason", Instant.now())));
  }

  @Test
  void rejectsWrongPath() {
    assertThrows(
        ApiException.class,
        () ->
            service.validatePath(
                new AiSuggestion("section", "root", "leaf", "Reason", Instant.now())));
  }

  @Test
  void rejectsRootWithParent() {
    assertThrows(
        ApiException.class,
        () -> service.save("new", new Category(null, "Name", "root", 1, null)));
  }

  @Test
  void rejectsWrongParentLevel() {
    assertThrows(
        ApiException.class,
        () -> service.save("new", new Category(null, "Name", "root", 3, null)));
  }

  @Test
  void rejectsHierarchyMutation() {
    assertThrows(
        ApiException.class,
        () -> service.save("leaf", new Category("leaf", "Name", null, 1, null)));
  }

  @Test
  void referencedCategoryCannotBeDeleted() {
    when(products.existsByCategoryId("leaf")).thenReturn(true);
    assertThrows(ApiException.class, () -> service.delete("leaf"));
    verify(repo, never()).delete(any());
  }

  @Test
  void parentCannotBeDeleted() {
    when(repo.findAll())
        .thenReturn(List.of(new Category("section", "Section", "root", 2, null)));
    assertThrows(ApiException.class, () -> service.delete("root"));
  }
}
