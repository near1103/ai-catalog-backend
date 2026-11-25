package com.nearapp.aicatalogbackend.service;

import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.AiSuggestion;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.model.Product;
import com.nearapp.aicatalogbackend.repository.CategoryRepository;
import com.nearapp.aicatalogbackend.repository.ProductRepository;
import com.nearapp.aicatalogbackend.storage.FileStorageService;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CategoryService {
  private static final Logger log = LoggerFactory.getLogger(CategoryService.class);
  private final CategoryRepository categories;
  private final ProductRepository products;

  private final FileStorageService storage;

  public CategoryService(CategoryRepository categories, ProductRepository products, FileStorageService storage) {
    this.categories = categories;
    this.products = products;
    this.storage = storage;
  }

  public List<Category> list() {
    return categories.findAll();
  }

  public Category get(String id) {
    return categories.findById(id).orElseThrow(ApiException::missing);
  }

  public Category leaf(String id) {
    var c = get(id);
    if (c.level() != 3) throw ApiException.bad("Product category must be a level 3 leaf");
    return c;
  }

  public synchronized Category save(String id, Category input) {
    if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw ApiException.bad("Invalid category identifier");
    if (input.id() != null && !id.equals(input.id()))
      throw ApiException.bad("Category id mismatch");
    if (input.level() == 1) {
      if (input.parentId() != null) throw ApiException.bad("Root cannot have a parent");
    } else {
      if (input.parentId() == null || input.parentId().equals(id))
        throw ApiException.bad("Invalid parent");
      var parent = get(input.parentId());
      if (parent.level() != input.level() - 1) throw ApiException.bad("Invalid category level");
    }
    var old = categories.findById(id);
    if (old.isPresent()
        && (old.get().level() != input.level()
            || !Objects.equals(old.get().parentId(), input.parentId())))
      throw ApiException.bad("Category hierarchy is immutable; create a new category instead");
    String key = old.filter(c -> Objects.equals(c.imageUrl(), input.imageUrl()))
        .map(Category::imageStorageKey).orElse(null);
    var saved = categories.save(
        new Category(id, input.name(), input.parentId(), input.level(), input.imageUrl(), key));
    old.filter(c -> !Objects.equals(c.imageUrl(), input.imageUrl()))
        .ifPresent(c -> cleanup(id, c.imageStorageKey()));
    return saved;
  }

  public synchronized Category uploadImage(String id, MultipartFile file) {
    var current = get(id);
    var uploaded = storage.uploadCategory(id, file);
    Category saved;
    try {
      saved = categories.save(new Category(id, current.name(), current.parentId(), current.level(),
          uploaded.url(), uploaded.storageKey()));
    } catch (RuntimeException failure) {
      cleanup(id, uploaded.storageKey());
      throw failure;
    }
    cleanup(id, current.imageStorageKey());
    return saved;
  }

  private void cleanup(String id, String key) {
    if (key == null) return;
    try {
      storage.delete(key);
    } catch (RuntimeException failure) {
      log.warn("Image cleanup failed for category {}, storage key {}. Manual cleanup required", id, key, failure);
    }
  }

  public synchronized void delete(String id) {
    var category = get(id);
    if (list().stream().anyMatch(c -> id.equals(c.parentId())) || products.existsByCategoryId(id))
      throw ApiException.bad("Category is referenced by children or products");
    categories.delete(id);
    cleanup(id, category.imageStorageKey());
  }

  public void validatePath(AiSuggestion suggestion) {
    if (suggestion == null || suggestion.reason() == null || suggestion.reason().isBlank())
      throw ApiException.bad("Invalid AI suggestion");
    var root = get(suggestion.rootCategoryId());
    var section = get(suggestion.sectionCategoryId());
    var leaf = leaf(suggestion.leafCategoryId());
    if (root.level() != 1
        || root.parentId() != null
        || section.level() != 2
        || !root.id().equals(section.parentId())
        || !section.id().equals(leaf.parentId()))
      throw ApiException.bad("Invalid AI category path");
  }

  static List<String> normalizeProposalNames(List<String> names) {
    if (names == null || names.isEmpty() || names.size() > 3)
      throw ApiException.bad("Provide one to three category names");
    return names.stream().map(name -> {
      String stripped = name == null ? "" : name.strip();
      if (stripped.isEmpty() || stripped.length() > 200)
        throw ApiException.bad("Category names must contain 1 to 200 characters");
      return stripped;
    }).toList();
  }

  static void validateNewPath(String parentId, List<String> names, List<Category> tree) {
    int parentLevel = 0;
    if (parentId != null) {
      var parent = tree.stream().filter(c -> parentId.equals(c.id())).findFirst()
          .orElseThrow(() -> ApiException.conflict("Proposed parent category no longer exists"));
      parentLevel = parent.level();
      boolean validParent = switch (parentLevel) {
        case 1 -> parent.parentId() == null;
        case 2 -> tree.stream().anyMatch(c -> c.level() == 1 && c.parentId() == null
            && c.id().equals(parent.parentId()));
        default -> false;
      };
      if (!validParent)
        throw ApiException.conflict("Proposed parent no longer has a valid category path");
    }
    if (parentLevel + names.size() != 3)
      throw ApiException.conflict("Proposed path must end at category level 3");
    if (tree.stream().anyMatch(c -> Objects.equals(c.parentId(), parentId)
        && c.name().strip().equalsIgnoreCase(names.getFirst())))
      throw ApiException.conflict("A sibling category named '" + names.getFirst() + "' already exists");
  }
}
