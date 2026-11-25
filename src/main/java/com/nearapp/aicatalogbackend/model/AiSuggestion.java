package com.nearapp.aicatalogbackend.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiSuggestion(
    String rootCategoryId,
    String sectionCategoryId,
    String leafCategoryId,
    String reason,
    Instant generatedAt,
    Type type,
    Proposal proposal) {
  public enum Type { NEW_CATEGORY }

  public record Proposal(String id,
      @JsonInclude(JsonInclude.Include.ALWAYS) String parentId, List<String> names) {
    public Proposal {
      names = List.copyOf(names);
    }
  }

  public AiSuggestion(
      String rootCategoryId, String sectionCategoryId, String leafCategoryId,
      String reason, Instant generatedAt) {
    this(rootCategoryId, sectionCategoryId, leafCategoryId, reason, generatedAt, null, null);
  }

  public static AiSuggestion newCategory(String reason, Proposal proposal) {
    return new AiSuggestion(null, null, null, reason, null, Type.NEW_CATEGORY, proposal);
  }
}
