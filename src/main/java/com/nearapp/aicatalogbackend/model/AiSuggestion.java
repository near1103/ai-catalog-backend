package com.nearapp.aicatalogbackend.model;

import java.time.Instant;

public record AiSuggestion(
    String rootCategoryId,
    String sectionCategoryId,
    String leafCategoryId,
    String reason,
    Instant generatedAt) {}
