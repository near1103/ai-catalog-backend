package com.nearapp.aicatalogbackend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nearapp.aicatalogbackend.controller.PublicCatalogController;
import com.nearapp.aicatalogbackend.dto.ProductInput;
import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.exception.ApiExceptionHandler;
import com.nearapp.aicatalogbackend.model.*;
import com.nearapp.aicatalogbackend.repository.CategoryRepository;
import com.nearapp.aicatalogbackend.repository.ProductRepository;
import com.nearapp.aicatalogbackend.service.CategoryService;
import com.nearapp.aicatalogbackend.service.CategorySuggestionService;
import com.nearapp.aicatalogbackend.service.ProductService;
import com.nearapp.aicatalogbackend.storage.FileStorageService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CatalogEvaluationTests {
  @Test
  void catalogExposesOnlyPublishedProductsThroughPublicEndpoints() throws Exception {
    var categoryRepo = mock(CategoryRepository.class);
    var productRepo = mock(ProductRepository.class);
    var storage = mock(FileStorageService.class);
    var categoryRows = new LinkedHashMap<String, Category>();
    var productRows = new LinkedHashMap<String, Product>();
    when(categoryRepo.findById(any())).thenAnswer(call -> Optional.ofNullable(categoryRows.get(call.getArgument(0))));
    when(categoryRepo.findAll()).thenAnswer(call -> List.copyOf(categoryRows.values()));
    when(categoryRepo.save(any())).thenAnswer(call -> {
      Category category = call.getArgument(0);
      categoryRows.put(category.id(), category);
      return category;
    });
    when(productRepo.findById(any())).thenAnswer(call -> Optional.ofNullable(productRows.get(call.getArgument(0))));
    when(productRepo.findActive()).thenAnswer(call -> productRows.values().stream()
        .filter(p -> p.status() == ProductStatus.ACTIVE).toList());
    when(productRepo.save(any())).thenAnswer(call -> {
      Product product = call.getArgument(0);
      productRows.put(product.id(), product);
      return product;
    });
    when(storage.upload(any(), any())).thenReturn(new FileStorageService.StoredFile("https://example.test/image", "image"));

    var categories = new CategoryService(categoryRepo, productRepo, storage);
    var products = new ProductService(productRepo, categories, storage, mock(CategorySuggestionService.class));
    categories.save("root", new Category("root", "Electronics", null, 1, null));
    assertEquals("root", categories.save("section", new Category("section", "Mobile", "root", 2, null)).parentId());
    assertEquals("section", categories.save("leaf", new Category("leaf", "Phones", "section", 3, null)).parentId());

    var input = new ProductInput("Phone", "Description", Map.of("color", "black"));
    var draft = products.create(input);
    assertEquals(ProductStatus.DRAFT, products.get(draft.id()).status());
    var active = products.create(input);
    var updated = products.update(active.id(), new ProductInput("Updated phone", "New description", Map.of("color", "white")));
    assertEquals("Updated phone", updated.name());
    assertEquals("New description", updated.description());
    assertEquals(Map.of("color", "white"), updated.characteristics());
    assertThrows(ApiException.class, () -> products.category(active.id(), "section"));
    assertEquals(CategorySource.MANUAL, products.category(active.id(), "leaf").categorySource());
    var image = new MockMultipartFile("file", new byte[] {1});
    products.upload(active.id(), image);
    assertEquals(ProductStatus.ACTIVE, products.publish(active.id()).status());
    var inactive = products.create(input);
    products.category(inactive.id(), "leaf");
    products.upload(inactive.id(), image);
    products.publish(inactive.id());
    assertEquals(ProductStatus.INACTIVE, products.deactivate(inactive.id()).status());

    var mvc = MockMvcBuilders.standaloneSetup(new PublicCatalogController(categories, products))
        .setControllerAdvice(new ApiExceptionHandler()).build();
    mvc.perform(get("/api/public/categories")).andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[2].parentId").value("section"));
    mvc.perform(get("/api/public/products")).andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(active.id()));
    mvc.perform(get("/api/public/products/{id}", active.id())).andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Updated phone")).andExpect(jsonPath("$.categoryId").value("leaf"));
    mvc.perform(get("/api/public/products/{id}", draft.id())).andExpect(status().isNotFound());
    mvc.perform(get("/api/public/products/{id}", inactive.id())).andExpect(status().isNotFound());
  }
}
