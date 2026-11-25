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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nearapp.aicatalogbackend.controller.AdminProductController;
import com.nearapp.aicatalogbackend.dto.ProductInput;
import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.exception.ApiExceptionHandler;
import com.nearapp.aicatalogbackend.model.AiSuggestion;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.model.CategorySource;
import com.nearapp.aicatalogbackend.model.Product;
import com.nearapp.aicatalogbackend.model.ProductImage;
import com.nearapp.aicatalogbackend.model.ProductStatus;
import com.nearapp.aicatalogbackend.repository.ProductRepository;
import com.nearapp.aicatalogbackend.repository.ProductRepository.CategoryCreation;
import com.nearapp.aicatalogbackend.service.CategoryService;
import com.nearapp.aicatalogbackend.service.CategorySuggestionService;
import com.nearapp.aicatalogbackend.service.ProductService;
import com.nearapp.aicatalogbackend.storage.FileStorageService;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
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
    var suggestion = new AiSuggestion("root", "section", "other", "Reason", Instant.now());
    when(ai.suggest(any(), any())).thenReturn(suggestion);
    service.suggest("p");
    var accepted = service.acceptSuggestion("p");
    assertEquals(CategorySource.AI, accepted.categorySource());
    assertEquals("other", accepted.categoryId());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void confirmsShortestMissingPathAndRejectsReplay(int missingLevels) {
    var root = new Category("root", "Electronics", null, 1, null);
    var section = new Category("section", "Networking", "root", 2, null);
    var tree = new ArrayList<>(List.of(root, section).subList(0, 3 - missingLevels));
    String parent = tree.isEmpty() ? null : tree.getLast().id();
    var names = List.of("Electronics", "Networking", "  Mesh systems  ").subList(3 - missingLevels, 3);
    when(categories.list()).thenReturn(tree);
    when(ai.suggest(any(), eq(tree))).thenReturn(AiSuggestion.newCategory("Missing category",
        new AiSuggestion.Proposal("proposal", parent, names)));
    mockCategoryCreation(tree);
    images(current.images(), ProductStatus.ACTIVE);

    var suggested = service.suggest("p");
    assertEquals("leaf", suggested.categoryId());
    assertEquals(3 - missingLevels, tree.size());
    assertEquals("Mesh systems", suggested.aiSuggestion().proposal().names().getLast());
    assertThrows(ApiException.class, () -> service.acceptSuggestion("p"));
    assertEquals(409, assertThrows(ApiException.class,
        () -> service.createSuggestedCategory("p", "obsolete", names)).status().value());

    var edited = new ArrayList<>(names);
    edited.set(edited.size() - 1, "  Mesh Wi-Fi systems  ");
    var confirmed = service.createSuggestedCategory("p", "proposal", edited);
    assertEquals(ProductStatus.ACTIVE, confirmed.status());
    assertEquals(CategorySource.AI, confirmed.categorySource());
    assertNull(confirmed.aiSuggestion());
    assertEquals(3, tree.size());
    assertEquals(confirmed.categoryId(), tree.getLast().id());
    assertEquals("Mesh Wi-Fi systems", tree.getLast().name());
    String expectedParent = parent;
    for (int index = 3 - missingLevels; index < 3; index++) {
      var category = tree.get(index);
      assertEquals(expectedParent, category.parentId());
      assertEquals(index + 1, category.level());
      expectedParent = category.id();
    }

    service.update("p", new ProductInput("Newer name", "Description", current.characteristics()));
    var afterEdit = current;
    assertEquals(409, assertThrows(ApiException.class,
        () -> service.createSuggestedCategory("p", "proposal", edited)).status().value());
    assertEquals(afterEdit, current);
    assertEquals(3, tree.size());
  }

  @Test
  void rejectsConflictsAndCanDiscardNewProposal() {
    var tree = new ArrayList<>(List.of(new Category("root", "Electronics", null, 1, null)));
    when(categories.list()).thenReturn(tree);
    when(ai.suggest(any(), eq(tree))).thenReturn(AiSuggestion.newCategory("Missing category",
        new AiSuggestion.Proposal("proposal", "root", List.of("Networking", "Mesh"))));
    mockCategoryCreation(tree);
    var suggested = service.suggest("p");
    assertEquals(400, assertThrows(ApiException.class,
        () -> service.createSuggestedCategory("p", "proposal", List.of("Networking"))).status().value());
    assertEquals(400, assertThrows(ApiException.class,
        () -> service.createSuggestedCategory("p", "proposal", List.of("Networking", "   "))).status().value());
    tree.add(new Category("section", "Networking", "root", 2, null));
    assertEquals(409, assertThrows(ApiException.class,
        () -> service.createSuggestedCategory("p", "proposal", List.of("  NETWORKING  ", "Mesh"))).status().value());
    assertEquals(suggested, current);
    assertNull(service.rejectSuggestion("p").aiSuggestion());
  }

  private void mockCategoryCreation(List<Category> tree) {
    when(repo.createCategories(eq("p"), any())).thenAnswer(invocation -> {
      BiFunction<Product, List<Category>, CategoryCreation> prepare = invocation.getArgument(1);
      var creation = prepare.apply(current, List.copyOf(tree));
      tree.addAll(creation.categories());
      current = creation.product();
      return current;
    });
  }

  @Test
  void adminEndpointsSerializeRootProposalAndReturnConflictOnReplay() throws Exception {
    var tree = new ArrayList<Category>();
    when(categories.list()).thenReturn(tree);
    when(ai.suggest(any(), eq(tree))).thenReturn(AiSuggestion.newCategory("Empty tree",
        new AiSuggestion.Proposal("proposal", null, List.of("Electronics", "Networking", "Mesh"))));
    mockCategoryCreation(tree);
    var mvc = MockMvcBuilders.standaloneSetup(new AdminProductController(service))
        .setControllerAdvice(new ApiExceptionHandler()).build();
    var product = "/api/admin/products/p";
    var suggest = product + "/ai-suggestion";
    mvc.perform(get(product)).andExpect(status().isOk())
        .andExpect(jsonPath("$.aiSuggestion.rootCategoryId").value("root"))
        .andExpect(jsonPath("$.aiSuggestion.generatedAt").exists())
        .andExpect(jsonPath("$.aiSuggestion.type").doesNotHaveJsonPath());
    mvc.perform(post(suggest)).andExpect(status().isOk())
        .andExpect(jsonPath("$.aiSuggestion.type").value("NEW_CATEGORY"))
        .andExpect(jsonPath("$.aiSuggestion.proposal.parentId").hasJsonPath())
        .andExpect(jsonPath("$.aiSuggestion.proposal.parentId").doesNotExist())
        .andExpect(jsonPath("$.aiSuggestion.leafCategoryId").doesNotHaveJsonPath());
    mvc.perform(get(product)).andExpect(status().isOk())
        .andExpect(jsonPath("$.aiSuggestion.proposal.id").value("proposal"));
    var confirm = post(suggest + "/create-category").contentType("application/json")
        .content("""
            {"proposalId":"proposal","names":["Electronics","Networking","Mesh"]}
            """);
    mvc.perform(confirm).andExpect(status().isOk())
        .andExpect(jsonPath("$.categorySource").value("AI"))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.aiSuggestion").doesNotExist());
    mvc.perform(confirm).andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("Category proposal is no longer current. Generate a new suggestion"));
  }

  @Test
  void rejectingSuggestionDoesNotChangeAssignedCategory() {
    var result = service.rejectSuggestion("p");
    assertNull(result.aiSuggestion());
    assertEquals("leaf", result.categoryId());
    assertEquals(CategorySource.MANUAL, result.categorySource());
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
