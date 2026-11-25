package com.nearapp.aicatalogbackend.service;

import com.nearapp.aicatalogbackend.dto.ProductInput;
import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.AiSuggestion;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.model.CategorySource;
import com.nearapp.aicatalogbackend.model.Product;
import com.nearapp.aicatalogbackend.model.ProductImage;
import com.nearapp.aicatalogbackend.model.ProductStatus;
import com.nearapp.aicatalogbackend.repository.ProductRepository;
import com.nearapp.aicatalogbackend.repository.ProductRepository.CategoryCreation;
import com.nearapp.aicatalogbackend.storage.FileStorageService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProductService {
  private static final Logger log = LoggerFactory.getLogger(ProductService.class);
  private final ProductRepository products;
  private final CategoryService categories;
  private final FileStorageService storage;
  private final CategorySuggestionService ai;

  public ProductService(
      ProductRepository products,
      CategoryService categories,
      FileStorageService storage,
      CategorySuggestionService ai) {
    this.products = products;
    this.categories = categories;
    this.storage = storage;
    this.ai = ai;
  }

  public List<Product> list(boolean publicOnly) {
    return publicOnly ? products.findActive() : products.findAll();
  }

  public Product get(String id) {
    return products.findById(id).orElseThrow(ApiException::missing);
  }

  public Product getPublic(String id) {
    var p = get(id);
    if (p.status() != ProductStatus.ACTIVE) throw ApiException.missing();
    return p;
  }

  public synchronized Product create(ProductInput input) {
    var now = Instant.now();
    return products.save(
        new Product(
            UUID.randomUUID().toString(),
            input.name(),
            input.description(),
            Map.copyOf(input.characteristics()),
            List.of(),
            null,
            ProductStatus.DRAFT,
            null,
            null,
            now,
            now));
  }

  private Product save(
      Product p,
      String name,
      String description,
      Map<String, String> chars,
      List<ProductImage> images,
      String category,
      ProductStatus status,
      AiSuggestion suggestion,
      CategorySource source) {
    return products.save(
        new Product(
            p.id(),
            name,
            description,
            Map.copyOf(chars),
            List.copyOf(images),
            category,
            status,
            suggestion,
            source,
            p.createdAt(),
            Instant.now()));
  }

  private Product state(Product p, List<ProductImage> images, ProductStatus status) {
    return save(
        p,
        p.name(),
        p.description(),
        p.characteristics(),
        images,
        p.categoryId(),
        status,
        p.aiSuggestion(),
        p.categorySource());
  }

  public synchronized Product update(String id, ProductInput input) {
    var p = get(id);
    boolean changed =
        !Objects.equals(p.name(), input.name())
            || !Objects.equals(p.description(), input.description())
            || !Objects.equals(p.characteristics(), input.characteristics());
    return save(
        p,
        input.name(),
        input.description(),
        input.characteristics(),
        p.images(),
        p.categoryId(),
        p.status(),
        changed ? null : p.aiSuggestion(),
        p.categorySource());
  }

  public synchronized Product category(String id, String categoryId) {
    categories.leaf(categoryId);
    var p = get(id);
    return save(
        p,
        p.name(),
        p.description(),
        p.characteristics(),
        p.images(),
        categoryId,
        p.status(),
        p.aiSuggestion(),
        CategorySource.MANUAL);
  }

  public synchronized Product suggest(String id) {
    var p = get(id);
    var tree = categories.list();
    var suggestion = ai.suggest(p, tree);
    try {
      if (suggestion != null && suggestion.type() == AiSuggestion.Type.NEW_CATEGORY) {
        var proposal = suggestion.proposal();
        if (proposal == null || proposal.id() == null || proposal.id().isBlank()
            || suggestion.reason() == null || suggestion.reason().isBlank())
          throw ApiException.bad("Invalid category proposal");
        var names = CategoryService.normalizeProposalNames(proposal.names());
        CategoryService.validateNewPath(proposal.parentId(), names, tree);
        suggestion = AiSuggestion.newCategory(suggestion.reason(),
            new AiSuggestion.Proposal(proposal.id(), proposal.parentId(), names));
      } else {
        categories.validatePath(suggestion);
      }
    } catch (ApiException e) {
      throw ApiException.external(
          "Gemini returned an invalid category path; select a category manually");
    }
    return save(
        p,
        p.name(),
        p.description(),
        p.characteristics(),
        p.images(),
        p.categoryId(),
        p.status(),
        suggestion,
        p.categorySource());
  }

  public synchronized Product createSuggestedCategory(String id, String proposalId, List<String> names) {
    return products.createCategories(id, (p, tree) -> {
      var suggestion = p.aiSuggestion();
      var proposal = suggestion == null ? null : suggestion.proposal();
      if (proposal == null || suggestion.type() != AiSuggestion.Type.NEW_CATEGORY
          || !proposal.id().equals(proposalId))
        throw ApiException.conflict("Category proposal is no longer current. Generate a new suggestion");

      var normalized = CategoryService.normalizeProposalNames(names);
      if (normalized.size() != proposal.names().size())
        throw ApiException.bad("Keep the same number of category levels as the proposal");
      CategoryService.validateNewPath(proposal.parentId(), normalized, tree);

      String parentId = proposal.parentId();
      int level = 4 - normalized.size();
      var created = new ArrayList<Category>();
      for (var name : normalized) {
        String categoryId = UUID.randomUUID().toString();
        created.add(new Category(categoryId, name, parentId, level++, null));
        parentId = categoryId;
      }
      var updated = new Product(p.id(), p.name(), p.description(), p.characteristics(), p.images(),
          parentId, p.status(), null, CategorySource.AI, p.createdAt(), Instant.now());
      return new CategoryCreation(updated, created);
    });
  }

  public synchronized Product acceptSuggestion(String id) {
    var p = get(id);
    if (p.aiSuggestion() == null) throw ApiException.bad("No suggestion to accept");
    if (p.aiSuggestion().type() == AiSuggestion.Type.NEW_CATEGORY)
      throw ApiException.bad("Confirm new categories using create-category");
    categories.validatePath(p.aiSuggestion());
    return save(
        p,
        p.name(),
        p.description(),
        p.characteristics(),
        p.images(),
        p.aiSuggestion().leafCategoryId(),
        p.status(),
        p.aiSuggestion(),
        CategorySource.AI);
  }

  public synchronized Product rejectSuggestion(String id) {
    var p = get(id);
    return save(
        p,
        p.name(),
        p.description(),
        p.characteristics(),
        p.images(),
        p.categoryId(),
        p.status(),
        null,
        p.categorySource());
  }

  public synchronized Product publish(String id) {
    var p = get(id);
    if (p.categoryId() == null) throw ApiException.bad("Select a leaf category before publication");
    categories.leaf(p.categoryId());
    if (p.images().isEmpty() || p.images().stream().filter(ProductImage::isPrimary).count() != 1)
      throw ApiException.bad("Publication requires images and exactly one primary image");
    return state(p, p.images(), ProductStatus.ACTIVE);
  }

  public synchronized Product deactivate(String id) {
    var p = get(id);
    return state(p, p.images(), ProductStatus.INACTIVE);
  }

  public synchronized Product upload(String id, MultipartFile file) {
    var p = get(id);
    var uploaded = storage.upload(id, file);
    var images = new ArrayList<>(p.images());
    images.add(new ProductImage(uploaded.url(), uploaded.storageKey(), images.isEmpty()));
    try {
      return state(p, images, p.status());
    } catch (RuntimeException failure) {
      try {
        storage.delete(uploaded.storageKey());
      } catch (RuntimeException cleanup) {
        log.error(
            "Upload rollback failed for product {}, storage key {}. Manual cleanup required",
            id,
            uploaded.storageKey());
      }
      throw failure;
    }
  }

  private void index(Product p, int index) {
    if (index < 0 || index >= p.images().size()) throw ApiException.missing();
  }

  public synchronized Product primary(String id, int index) {
    var p = get(id);
    index(p, index);
    var images = new ArrayList<ProductImage>();
    for (int i = 0; i < p.images().size(); i++) {
      var image = p.images().get(i);
      images.add(new ProductImage(image.url(), image.storageKey(), i == index));
    }
    return state(p, images, p.status());
  }

  public synchronized Product deleteImage(String id, int index) {
    var p = get(id);
    index(p, index);
    if (p.status() == ProductStatus.ACTIVE && p.images().size() == 1)
      throw ApiException.bad("Deactivate product before removing its last image");
    var image = p.images().get(index);
    if (p.status() == ProductStatus.ACTIVE) p = state(p, p.images(), ProductStatus.INACTIVE);
    deleteManaged(p.id(), image.storageKey());
    var images = new ArrayList<>(p.images());
    images.remove(index);
    if (image.isPrimary() && !images.isEmpty()) {
      var first = images.getFirst();
      images.set(0, new ProductImage(first.url(), first.storageKey(), true));
    }
    return state(p, images, p.status());
  }

  private void deleteManaged(String id, String key) {
    if (key == null) return;
    try {
      storage.delete(key);
    } catch (RuntimeException failure) {
      log.warn(
          "Asset deletion failed for product {}, storage key {}. Product retained for retry",
          id,
          key);
      throw ApiException.external(
          "Image deletion failed. Product retained inactive; retry deletion");
    }
  }

  public synchronized void delete(String id) {
    var p = get(id);
    if (p.status() != ProductStatus.INACTIVE) p = state(p, p.images(), ProductStatus.INACTIVE);
    for (var image : p.images()) deleteManaged(id, image.storageKey());
    products.delete(id);
  }
}
