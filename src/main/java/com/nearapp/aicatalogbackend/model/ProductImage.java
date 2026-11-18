package com.nearapp.aicatalogbackend.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProductImage(
    String url, String storageKey, @JsonProperty("isPrimary") boolean isPrimary) {}
