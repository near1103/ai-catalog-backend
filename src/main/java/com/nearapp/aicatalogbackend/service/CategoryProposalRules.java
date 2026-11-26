package com.nearapp.aicatalogbackend.service;

import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.Category;
import java.util.List;
import java.util.Objects;

final class CategoryProposalRules {
  private CategoryProposalRules() {}

  static List<String> normalizeProposalNames(List<String> names) {
    if (names == null || names.isEmpty() || names.size() > 3)
      throw ApiException.bad("Provide one to three category names");
    return names.stream().map(name -> {
      String stripped = name == null ? "" : name.strip();
      if (stripped.isEmpty() || stripped.length() > 200)
        throw ApiException.bad("Category names must contain 1 to 200 characters");
      return stripped;
    }).toList();
  }

  static void validateNewPath(String parentId, List<String> names, List<Category> tree) {
    int parentLevel = 0;
    if (parentId != null) {
      var parent = tree.stream().filter(c -> parentId.equals(c.id())).findFirst()
          .orElseThrow(() -> ApiException.conflict("Proposed parent category no longer exists"));
      parentLevel = parent.level();
      boolean validParent = switch (parentLevel) {
        case 1 -> parent.parentId() == null;
        case 2 -> tree.stream().anyMatch(c -> c.level() == 1 && c.parentId() == null
            && c.id().equals(parent.parentId()));
        default -> false;
      };
      if (!validParent)
        throw ApiException.conflict("Proposed parent no longer has a valid category path");
    }
    if (parentLevel + names.size() != 3)
      throw ApiException.conflict("Proposed path must end at category level 3");
    if (tree.stream().anyMatch(c -> Objects.equals(c.parentId(), parentId)
        && c.name().strip().equalsIgnoreCase(names.getFirst())))
      throw ApiException.conflict("A sibling category named '" + names.getFirst() + "' already exists");
  }
}
