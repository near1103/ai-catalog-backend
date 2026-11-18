package com.nearapp.aicatalogbackend.controller;

import com.nearapp.aicatalogbackend.dto.PublicProduct;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.service.CategoryService;
import com.nearapp.aicatalogbackend.service.ProductService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class PublicCatalogController {
  private final CategoryService categories;
  private final ProductService products;

  public PublicCatalogController(CategoryService categories, ProductService products) {
    this.categories = categories;
    this.products = products;
  }

  @GetMapping("/categories")
  public List<Category> categories() {
    return categories.list();
  }

  @GetMapping("/categories/{id}")
  public Category category(@PathVariable String id) {
    return categories.get(id);
  }

  @GetMapping("/products")
  public List<PublicProduct> products() {
    return products.list(true).stream().map(PublicProduct::from).toList();
  }

  @GetMapping("/products/{id}")
  public PublicProduct product(@PathVariable String id) {
    return PublicProduct.from(products.getPublic(id));
  }
}
