package com.nearapp.aicatalogbackend.repository;

import static com.nearapp.aicatalogbackend.repository.FirestoreAccess.await;
import static com.nearapp.aicatalogbackend.repository.FirestoreAccess.id;

import com.google.cloud.firestore.Firestore;
import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.model.Product;
import java.util.HashMap;
import java.util.List;
import java.util.function.BiFunction;
import org.springframework.stereotype.Repository;

@Repository
public class FirestoreCategoryProposalRepository implements CategoryProposalRepository {
  private final Firestore db;

  public FirestoreCategoryProposalRepository(Firestore db) {
    this.db = db;
  }

  @Override
  public Product createCategories(String productId,
      BiFunction<Product, List<Category>, CategoryCreation> prepare) {
    id(productId);
    var productRef = db.collection("products").document(productId);
    var categoryCollection = db.collection("categories");
    return await(db.runTransaction(transaction -> {
      var document = transaction.get(productRef).get();
      if (!document.exists()) throw ApiException.missing();
      var tree = transaction.get(categoryCollection).get().getDocuments().stream()
          .map(FirestoreCategoryRepository::read).toList();
      var creation = prepare.apply(FirestoreProductRepository.read(document), tree);
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
}
