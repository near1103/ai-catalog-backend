package com.nearapp.aicatalogbackend.repository;

import static com.nearapp.aicatalogbackend.repository.FirestoreAccess.await;
import static com.nearapp.aicatalogbackend.repository.FirestoreAccess.id;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.AiSuggestion;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.model.CategorySource;
import com.nearapp.aicatalogbackend.model.Product;
import com.nearapp.aicatalogbackend.model.ProductImage;
import com.nearapp.aicatalogbackend.model.ProductStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import org.springframework.stereotype.Repository;

@Repository
public class FirestoreProductRepository implements ProductRepository {
  private final Firestore db;
  private final CollectionReference collection;

  public FirestoreProductRepository(Firestore db) {
    this.db = db;
    collection = db.collection("products");
  }

  private static Instant instant(Object v) {
    return v == null
        ? null
        : v instanceof Timestamp t
            ? t.toDate().toInstant()
            : Instant.parse(v.toString());
  }

  @SuppressWarnings("unchecked")
  private Product read(DocumentSnapshot d) {
    var m = d.getData();
    var images = new ArrayList<ProductImage>();
    for (var image : (List<Map<String, Object>>) m.getOrDefault("images", List.of()))
      images.add(
          new ProductImage(
              (String) image.get("url"),
              (String) image.get("storageKey"),
              Boolean.TRUE.equals(image.get("isPrimary"))));
    var suggestion = readSuggestion((Map<String, Object>) m.get("aiSuggestion"));
    return new Product(
        d.getId(),
        d.getString("name"),
        d.getString("description"),
        (Map<String, String>) m.getOrDefault("characteristics", Map.of()),
        List.copyOf(images),
        d.getString("categoryId"),
        ProductStatus.valueOf(d.getString("status")),
        suggestion,
        m.get("categorySource") == null
            ? null
            : CategorySource.valueOf(d.getString("categorySource")),
        instant(m.get("createdAt")),
        instant(m.get("updatedAt")));
  }

  @SuppressWarnings("unchecked")
  private static AiSuggestion readSuggestion(Map<String, Object> data) {
    if (data == null) return null;
    var reason = (String) data.get("reason");
    if (AiSuggestion.Type.NEW_CATEGORY.name().equals(data.get("type"))) {
      var proposal = (Map<String, Object>) data.get("proposal");
      return AiSuggestion.newCategory(reason, new AiSuggestion.Proposal(
          (String) proposal.get("id"), (String) proposal.get("parentId"),
          (List<String>) proposal.get("names")));
    }
    return new AiSuggestion((String) data.get("rootCategoryId"),
        (String) data.get("sectionCategoryId"), (String) data.get("leafCategoryId"),
        reason, instant(data.get("generatedAt")));
  }

  private static Map<String, Object> suggestionFields(AiSuggestion suggestion) {
    if (suggestion == null) return null;
    var fields = new HashMap<String, Object>();
    fields.put("reason", suggestion.reason());
    if (suggestion.type() == AiSuggestion.Type.NEW_CATEGORY) {
      var proposal = suggestion.proposal();
      var path = new HashMap<String, Object>();
      path.put("id", proposal.id());
      path.put("parentId", proposal.parentId());
      path.put("names", proposal.names());
      fields.put("type", suggestion.type().name());
      fields.put("proposal", path);
    } else {
      fields.put("rootCategoryId", suggestion.rootCategoryId());
      fields.put("sectionCategoryId", suggestion.sectionCategoryId());
      fields.put("leafCategoryId", suggestion.leafCategoryId());
      fields.put("generatedAt", suggestion.generatedAt() == null ? null : suggestion.generatedAt().toString());
    }
    return fields;
  }

  public List<Product> findAll() {
    return await(collection.get()).getDocuments().stream().map(this::read).toList();
  }

  public List<Product> findActive() {
    return await(collection.whereEqualTo("status", "ACTIVE").get()).getDocuments().stream()
        .map(this::read)
        .toList();
  }

  public Optional<Product> findById(String value) {
    id(value);
    var d = await(collection.document(value).get());
    return d.exists() ? Optional.of(read(d)) : Optional.empty();
  }

  public boolean existsByCategoryId(String value) {
    return !await(collection.whereEqualTo("categoryId", value).limit(1).get()).isEmpty();
  }

  public Product save(Product p) {
    id(p.id());
    Map<String, Object> m = new HashMap<>();
    m.put("name", p.name());
    m.put("description", p.description());
    m.put("characteristics", p.characteristics());
    m.put(
        "images",
        p.images().stream()
            .map(
                i -> {
                  Map<String, Object> v = new HashMap<>();
                  v.put("url", i.url());
                  v.put("storageKey", i.storageKey());
                  v.put("isPrimary", i.isPrimary());
                  return v;
                })
            .toList());
    m.put("categoryId", p.categoryId());
    m.put("status", p.status().name());
    m.put("categorySource", p.categorySource() == null ? null : p.categorySource().name());
    m.put("aiSuggestion", suggestionFields(p.aiSuggestion()));
    m.put("createdAt", p.createdAt().toString());
    m.put("updatedAt", p.updatedAt().toString());
    await(collection.document(p.id()).set(m));
    return p;
  }

  @Override
  public Product createCategories(String productId,
      BiFunction<Product, List<Category>, CategoryCreation> prepare) {
    id(productId);
    var productRef = collection.document(productId);
    var categoryCollection = db.collection("categories");
    return await(db.runTransaction(transaction -> {
      var document = transaction.get(productRef).get();
      if (!document.exists()) throw ApiException.missing();
      var tree = transaction.get(categoryCollection).get().getDocuments().stream()
          .map(FirestoreCategoryRepository::read).toList();
      var creation = prepare.apply(read(document), tree);
      for (var category : creation.categories())
        transaction.create(categoryCollection.document(category.id()), FirestoreCategoryRepository.fields(category));
      var product = creation.product();
      var fields = new HashMap<String, Object>();
      fields.put("categoryId", product.categoryId());
      fields.put("categorySource", product.categorySource().name());
      fields.put("aiSuggestion", null);
      fields.put("updatedAt", product.updatedAt().toString());
      transaction.update(productRef, fields);
      return product;
    }));
  }

  public void delete(String value) {
    id(value);
    await(collection.document(value).delete());
  }
}
