package com.nearapp.aicatalogbackend.repository;

import com.nearapp.aicatalogbackend.model.Category;
import java.util.List;
import java.util.Optional;

public interface CategoryRepository {
  List<Category> findAll();

  Optional<Category> findById(String id);

  Category save(Category value);

  void delete(String id);
}
