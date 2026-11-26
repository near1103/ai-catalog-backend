package com.nearapp.aicatalogbackend.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.Category;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryProposalRulesTests {
  @Test
  void levelTwoParentMustStillHaveAValidRoot() {
    var section = new Category("section", "Networking", "root", 2, null);
    var root = new Category("root", "Electronics", null, 1, null);
    var names = CategoryProposalRules.normalizeProposalNames(List.of("  Mesh  "));

    assertEquals(List.of("Mesh"), names);
    assertDoesNotThrow(() -> CategoryProposalRules.validateNewPath("section", names, List.of(root, section)));
    var failure = assertThrows(ApiException.class,
        () -> CategoryProposalRules.validateNewPath("section", names, List.of(section)));
    assertEquals(409, failure.status().value());
  }
}
