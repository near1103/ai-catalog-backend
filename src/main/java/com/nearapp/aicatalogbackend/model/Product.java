package com.nearapp.aicatalogbackend.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Product(
    String id,
    String name,
    String description,
    Map<String, String> characteristics,
    List<ProductImage> images,
    String categoryId,
    ProductStatus status,
    AiSuggestion aiSuggestion,
    CategorySource categorySource,
    Instant createdAt,
    Instant updatedAt) {}
