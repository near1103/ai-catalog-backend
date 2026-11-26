package com.nearapp.aicatalogbackend.repository;

import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.model.Product;
import java.util.List;
import java.util.function.BiFunction;

public interface CategoryProposalRepository {
  record CategoryCreation(Product product, List<Category> categories) {}

  // The callback prepares writes from the current snapshots and may run again on retry.
  Product createCategories(String productId,
      BiFunction<Product, List<Category>, CategoryCreation> prepare);
}
