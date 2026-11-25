package com.nearapp.aicatalogbackend.repository;

import static com.nearapp.aicatalogbackend.repository.FirestoreAccess.await;
import static com.nearapp.aicatalogbackend.repository.FirestoreAccess.id;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.nearapp.aicatalogbackend.model.Category;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class FirestoreCategoryRepository implements CategoryRepository {
  private final CollectionReference collection;

  public FirestoreCategoryRepository(Firestore db) {
    collection = db.collection("categories");
  }

  static Category read(DocumentSnapshot d) {
    return new Category(
        d.getId(),
        d.getString("name"),
        d.getString("parentId"),
        d.getLong("level").intValue(),
        d.getString("imageUrl"),
        d.getString("imageStorageKey"));
  }

  public List<Category> findAll() {
    return await(collection.get()).getDocuments().stream().map(FirestoreCategoryRepository::read).toList();
  }

  public Optional<Category> findById(String value) {
    id(value);
    var d = await(collection.document(value).get());
    return d.exists() ? Optional.of(read(d)) : Optional.empty();
  }

  public Category save(Category c) {
    id(c.id());
    await(collection.document(c.id()).set(fields(c)));
    return c;
  }

  static Map<String, Object> fields(Category c) {
    Map<String, Object> m = new HashMap<>();
    m.put("name", c.name());
    m.put("parentId", c.parentId());
    m.put("level", c.level());
    m.put("imageUrl", c.imageUrl());
    m.put("imageStorageKey", c.imageStorageKey());
    return m;
  }

  public void delete(String value) {
    id(value);
    await(collection.document(value).delete());
  }
}
