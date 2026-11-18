package com.nearapp.aicatalogbackend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record Category(
    @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String id,
    @NotBlank @Size(max = 200) String name,
    String parentId,
    @Min(1) @Max(3) int level,
    String imageUrl,
    @JsonIgnore String imageStorageKey) {
  public Category(String id, String name, String parentId, int level, String imageUrl) {
    this(id, name, parentId, level, imageUrl, null);
  }
}
