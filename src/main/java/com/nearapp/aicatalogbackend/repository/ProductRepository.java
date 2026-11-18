package com.nearapp.aicatalogbackend.repository;

import com.nearapp.aicatalogbackend.model.Product;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
  List<Product> findAll();

  List<Product> findActive();

  Optional<Product> findById(String id);

  Product save(Product value);

  void delete(String id);

  boolean existsByCategoryId(String id);
}
