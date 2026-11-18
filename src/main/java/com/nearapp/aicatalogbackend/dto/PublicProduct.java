package com.nearapp.aicatalogbackend.dto;

import com.nearapp.aicatalogbackend.model.Product;
import java.util.List;
import java.util.Map;

public record PublicProduct(
    String id,
    String name,
    String description,
    Map<String, String> characteristics,
    List<Image> images,
    String categoryId) {

  public static PublicProduct from(Product p) {
    return new PublicProduct(
        p.id(),
        p.name(),
        p.description(),
        p.characteristics(),
        p.images().stream().map(i -> new Image(i.url(), i.isPrimary())).toList(),
        p.categoryId());
  }
}
