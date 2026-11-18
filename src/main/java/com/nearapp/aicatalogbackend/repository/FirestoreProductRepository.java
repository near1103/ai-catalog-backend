package com.nearapp.aicatalogbackend.repository;

import static com.nearapp.aicatalogbackend.repository.FirestoreAccess.await;
import static com.nearapp.aicatalogbackend.repository.FirestoreAccess.id;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.nearapp.aicatalogbackend.model.AiSuggestion;
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
import org.springframework.stereotype.Repository;

@Repository
public class FirestoreProductRepository implements ProductRepository {
  private final CollectionReference collection;

  public FirestoreProductRepository(Firestore db) {
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
    var a = (Map<String, Object>) m.get("aiSuggestion");
    AiSuggestion suggestion =
        a == null
            ? null
            : new AiSuggestion(
                (String) a.get("rootCategoryId"),
                (String) a.get("sectionCategoryId"),
                (String) a.get("leafCategoryId"),
                (String) a.get("reason"),
                instant(a.get("generatedAt")));
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
    var a = p.aiSuggestion();
    m.put(
        "aiSuggestion",
        a == null
            ? null
            : Map.of(
                "rootCategoryId",
                a.rootCategoryId(),
                "sectionCategoryId",
                a.sectionCategoryId(),
                "leafCategoryId",
                a.leafCategoryId(),
                "reason",
                a.reason(),
                "generatedAt",
                a.generatedAt().toString()));
    m.put("createdAt", p.createdAt().toString());
    m.put("updatedAt", p.updatedAt().toString());
    await(collection.document(p.id()).set(m));
    return p;
  }

  public void delete(String value) {
    id(value);
    await(collection.document(value).delete());
  }
}
