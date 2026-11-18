package com.nearapp.aicatalogbackend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
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
import com.nearapp.aicatalogbackend.model.AiSuggestion;
import com.nearapp.aicatalogbackend.model.CategorySource;
import com.nearapp.aicatalogbackend.model.Product;
import com.nearapp.aicatalogbackend.model.ProductImage;
import com.nearapp.aicatalogbackend.model.ProductStatus;
import com.nearapp.aicatalogbackend.repository.FirestoreProductRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FirestoreRepositoryTests {
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void productWritePreservesExternalImageAndAllFields() {
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
            new AiSuggestion("root", "section", "leaf", "Reason", now),
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
}
