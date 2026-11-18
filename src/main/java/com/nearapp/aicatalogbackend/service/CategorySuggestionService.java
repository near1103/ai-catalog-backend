package com.nearapp.aicatalogbackend.service;

import com.nearapp.aicatalogbackend.model.AiSuggestion;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.model.Product;
import java.util.List;

public interface CategorySuggestionService {
  AiSuggestion suggest(Product product, List<Category> categories);
}
