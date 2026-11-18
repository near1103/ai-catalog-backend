package com.nearapp.aicatalogbackend.setup;

import com.google.firebase.cloud.FirestoreClient;
import com.nearapp.aicatalogbackend.config.FirebaseConfig;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.model.CategorySource;
import com.nearapp.aicatalogbackend.model.Product;
import com.nearapp.aicatalogbackend.model.ProductImage;
import com.nearapp.aicatalogbackend.model.ProductStatus;
import com.nearapp.aicatalogbackend.repository.FirestoreCategoryRepository;
import com.nearapp.aicatalogbackend.repository.FirestoreProductRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

public final class SeedImportCommand {
  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException("Usage: path/to/catalog-seed-directory");
    var json = new ObjectMapper();
    var directory = Path.of(args[0]);
    var app = FirebaseConfig.initialize(System.getenv("FIREBASE_PROJECT_ID"));
    try (var db = FirestoreClient.getFirestore(app)) {
      var categories = new FirestoreCategoryRepository(db);
      var products = new FirestoreProductRepository(db);
      for (var node : json.readTree(Files.readString(directory.resolve("categories.json")))) {
        String id = node.path("id").asText();
        if (categories.findById(id).isEmpty())
          categories.save(
              new Category(
                  id,
                  node.path("name").asText(),
                  node.path("parentId").isNull() ? null : node.path("parentId").asText(),
                  node.path("level").asInt(),
                  node.path("imageUrl").asText()));
      }
      for (var node : json.readTree(Files.readString(directory.resolve("products.json")))) {
        String id = node.path("id").asText();
        if (products.findById(id).isPresent()) continue;
        Map<String, String> chars = new HashMap<>();
        node.path("characteristics")
            .properties()
            .forEach(e -> chars.put(e.getKey(), e.getValue().asText()));
        String url = node.path("imageUrl").asText();
        var images =
            url.isBlank() ? List.<ProductImage>of() : List.of(new ProductImage(url, null, true));
        var now = Instant.now();
        products.save(
            new Product(
                id,
                node.path("name").asText(),
                node.path("description").asText(),
                chars,
                images,
                node.path("categoryId").asText(),
                ProductStatus.DRAFT,
                null,
                CategorySource.MANUAL,
                now,
                now));
      }
      System.out.println(
          "Seed import complete. Imported products are DRAFT; review and publish through admin"
              + " API.");
    } finally {
      app.delete();
    }
  }
}
