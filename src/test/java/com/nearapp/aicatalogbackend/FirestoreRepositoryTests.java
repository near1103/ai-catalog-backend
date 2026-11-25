package com.nearapp.aicatalogbackend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.Transaction;
import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.AiSuggestion;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.model.CategorySource;
import com.nearapp.aicatalogbackend.model.Product;
import com.nearapp.aicatalogbackend.model.ProductImage;
import com.nearapp.aicatalogbackend.model.ProductStatus;
import com.nearapp.aicatalogbackend.repository.FirestoreProductRepository;
import com.nearapp.aicatalogbackend.repository.ProductRepository.CategoryCreation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class FirestoreRepositoryTests {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @SuppressWarnings({"unchecked", "rawtypes"})
  void productWritePreservesExternalImageAndAllFields(boolean newCategory) {
    var db = mock(Firestore.class);
    var collection = mock(CollectionReference.class);
    var doc = mock(DocumentReference.class);
    when(db.collection("products")).thenReturn(collection);
    when(collection.document("p")).thenReturn(doc);
    when(doc.set(any())).thenReturn(ApiFutures.immediateFuture(null));
    var now = Instant.now();
    var p =
        new Product(
            "p",
            "Phone",
            "Description",
            Map.of("color", "black"),
            List.of(new ProductImage("https://external/image", null, true)),
            "leaf",
            ProductStatus.DRAFT,
            newCategory
                ? AiSuggestion.newCategory("Reason",
                    new AiSuggestion.Proposal("proposal", null, List.of("Root", "Section", "Leaf")))
                : new AiSuggestion("root", "section", "leaf", "Reason", now),
            CategorySource.MANUAL,
            now,
            now);
    new FirestoreProductRepository(db).save(p);
    ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
    verify(doc).set(captor.capture());
    var written = captor.getValue();
    assertFalse(written.containsKey("categoryPath"));
    assertEquals("DRAFT", written.get("status"));
    assertEquals(now.toString(), written.get("createdAt"));
    var image = (Map) ((List) written.get("images")).getFirst();
    assertNull(image.get("storageKey"));
    assertEquals(true, image.get("isPrimary"));
    var snapshot = mock(DocumentSnapshot.class);
    when(snapshot.exists()).thenReturn(true);
    when(snapshot.getId()).thenReturn("p");
    when(snapshot.getData()).thenReturn(written);
    when(snapshot.getString(anyString())).thenAnswer(i -> written.get(i.getArgument(0)));
    when(doc.get()).thenReturn(ApiFutures.immediateFuture(snapshot));
    assertEquals(p, new FirestoreProductRepository(db).findById("p").orElseThrow());
  }

  @Test
  void activeQueryFiltersAtDatabase() {
    var db = mock(Firestore.class);
    var collection = mock(CollectionReference.class);
    var query = mock(Query.class);
    var snapshot = mock(QuerySnapshot.class);
    when(db.collection("products")).thenReturn(collection);
    when(collection.whereEqualTo("status", "ACTIVE")).thenReturn(query);
    when(query.get()).thenReturn(ApiFutures.immediateFuture(snapshot));
    when(snapshot.getDocuments()).thenReturn(List.of());
    assertTrue(new FirestoreProductRepository(db).findActive().isEmpty());
    verify(collection).whereEqualTo("status", "ACTIVE");
    verify(collection, never()).get();
  }

  @Test
  void invalidDocumentIdRejectedBeforeAccess() {
    var db = mock(Firestore.class);
    when(db.collection("products")).thenReturn(mock(CollectionReference.class));
    var repo = new FirestoreProductRepository(db);
    assertThrows(RuntimeException.class, () -> repo.findById("../secret"));
  }

  @Test
  void categoryCreationReadsThenWritesInOneTransactionAndPropagatesConflicts() {
    var db = mock(Firestore.class);
    var products = mock(CollectionReference.class);
    var categories = mock(CollectionReference.class);
    var productRef = mock(DocumentReference.class);
    var categoryRef = mock(DocumentReference.class);
    var transaction = mock(Transaction.class);
    var document = mock(DocumentSnapshot.class);
    var tree = mock(QuerySnapshot.class);
    when(db.collection("products")).thenReturn(products);
    when(db.collection("categories")).thenReturn(categories);
    when(products.document("p")).thenReturn(productRef);
    when(categories.document("leaf")).thenReturn(categoryRef);
    when(transaction.get(productRef)).thenReturn(ApiFutures.immediateFuture(document));
    when(transaction.get(categories)).thenReturn(ApiFutures.immediateFuture(tree));
    when(tree.getDocuments()).thenReturn(List.of());
    when(document.exists()).thenReturn(true);
    when(document.getId()).thenReturn("p");
    when(document.getData()).thenReturn(Map.of());
    when(document.getString("status")).thenReturn("ACTIVE");
    when(db.<Product>runTransaction(any())).thenAnswer(call -> {
      Transaction.Function<Product> callback = call.getArgument(0);
      try {
        return ApiFutures.immediateFuture(callback.updateCallback(transaction));
      } catch (Exception failure) {
        return ApiFutures.immediateFailedFuture(failure);
      }
    });
    var repository = new FirestoreProductRepository(db);
    var result = repository.createCategories("p", (p, rows) -> new CategoryCreation(
        new Product(p.id(), p.name(), p.description(), p.characteristics(), p.images(),
            "leaf", p.status(), null, CategorySource.AI, p.createdAt(), Instant.now()),
        List.of(new Category("leaf", "Mesh", "section", 3, null))));
    var ordered = inOrder(transaction);
    ordered.verify(transaction).get(productRef);
    ordered.verify(transaction).get(categories);
    ordered.verify(transaction).create(eq(categoryRef), any(Map.class));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
    ordered.verify(transaction).update(eq(productRef), fields.capture());
    assertEquals(ProductStatus.ACTIVE, result.status());
    assertEquals("leaf", fields.getValue().get("categoryId"));
    assertEquals("AI", fields.getValue().get("categorySource"));
    assertTrue(fields.getValue().containsKey("aiSuggestion"));
    assertNull(fields.getValue().get("aiSuggestion"));
    assertFalse(fields.getValue().containsKey("status"));

    var conflict = assertThrows(ApiException.class, () -> repository.createCategories("p", (p, rows) -> {
      throw ApiException.conflict("Stale proposal");
    }));
    assertEquals(409, conflict.status().value());
    verify(transaction).create(eq(categoryRef), any(Map.class));
    verify(transaction).update(eq(productRef), any(Map.class));
  }
}
