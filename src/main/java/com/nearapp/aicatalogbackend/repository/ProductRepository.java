package com.nearapp.aicatalogbackend.repository;

import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.model.Product;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

public interface ProductRepository {
  record CategoryCreation(Product product, List<Category> categories) {}

  List<Product> findAll();

  List<Product> findActive();

  Optional<Product> findById(String id);

  Product save(Product value);

  Product createCategories(String productId,
      BiFunction<Product, List<Category>, CategoryCreation> prepare);

  void delete(String id);

  boolean existsByCategoryId(String id);
}
