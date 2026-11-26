package com.nearapp.aicatalogbackend.service;

import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.AiSuggestion;
import com.nearapp.aicatalogbackend.model.Category;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.ai.converter.BeanOutputConverter;
import tools.jackson.databind.JsonNode;

final class CategorySuggestionParser {
  private CategorySuggestionParser() {}

  record Answer(
      String rootCategoryId, String sectionCategoryId, String leafCategoryId, String reason) {}

  record ProposalAnswer(
      String rootCategoryId, String sectionCategoryId, String leafCategoryId, String reason,
      String type, String parentId, List<JsonNode> names) {}

  static final BeanOutputConverter<ProposalAnswer> PROPOSAL_OUTPUT =
      new BeanOutputConverter<>(ProposalAnswer.class) {
        @Override
        protected String generateSchema() {
          return """
              {
                "oneOf": [
                  {
                    "type": "object",
                    "properties": {
                      "leafCategoryId": {"type": "string"},
                      "reason": {"type": "string"}
                    },
                    "required": ["leafCategoryId", "reason"],
                    "additionalProperties": false
                  },
                  {
                    "type": "object",
                    "properties": {
                      "type": {"const": "NEW_CATEGORY"},
                      "reason": {"type": "string"},
                      "parentId": {"type": ["string", "null"]},
                      "names": {
                        "type": "array", "minItems": 1, "maxItems": 3,
                        "items": {"type": "string", "minLength": 1, "maxLength": 200}
                      }
                    },
                    "required": ["type", "reason", "parentId", "names"],
                    "additionalProperties": false
                  }
                ]
              }
              """;
        }
      };

  static AiSuggestion toSuggestion(Answer answer) {
    return new AiSuggestion(answer.rootCategoryId(), answer.sectionCategoryId(),
        answer.leafCategoryId(), answer.reason(), Instant.now());
  }

  static AiSuggestion toSuggestion(ProposalAnswer answer, List<Category> tree) {
    if ("NEW_CATEGORY".equals(answer.type())) {
      if (answer.rootCategoryId() != null || answer.sectionCategoryId() != null
          || answer.leafCategoryId() != null)
        throw new IllegalArgumentException("Mixed category suggestion");
      if (answer.names() == null || answer.names().stream().anyMatch(name -> name == null || !name.isString()))
        throw new IllegalArgumentException("Expected category names as strings");
      var names = CategoryProposalRules.normalizeProposalNames(
          answer.names().stream().map(JsonNode::stringValue).toList());
      String parentId = null;
      if (answer.parentId() != null) {
        var returnedId = answer.parentId().strip();
        var matching = tree.stream().filter(c -> (c.level() == 1 || c.level() == 2)
            && c.id().equalsIgnoreCase(returnedId)).toList();
        parentId = matching.size() == 1 ? matching.getFirst().id() : returnedId;
        if (matching.size() == 1) {
          int remainingLevels = 3 - matching.getFirst().level();
          if (names.size() > remainingLevels)
            names = names.subList(names.size() - remainingLevels, names.size());
        }
      }
      return AiSuggestion.newCategory(answer.reason(),
          new AiSuggestion.Proposal(UUID.randomUUID().toString(), parentId, names));
    }
    if (answer.type() != null || answer.parentId() != null || answer.names() != null)
      throw new IllegalArgumentException("Invalid category suggestion type");
    var leaf = findCategory(tree, answer.leafCategoryId(), 3);
    var section = leaf == null ? null : findCategory(tree, leaf.parentId(), 2);
    var root = section == null ? null : findCategory(tree, section.parentId(), 1);
    return new AiSuggestion(root == null ? null : root.id(), section == null ? null : section.id(),
        answer.leafCategoryId(), answer.reason(), Instant.now());
  }

  private static Category findCategory(List<Category> tree, String id, int level) {
    return tree.stream().filter(c -> c.level() == level && c.id().equals(id))
        .findFirst().orElse(null);
  }

  static boolean validSuggestion(AiSuggestion suggestion, List<Category> tree) {
    if (suggestion.reason() == null || suggestion.reason().isBlank()) return false;
    if (suggestion.type() == AiSuggestion.Type.NEW_CATEGORY) {
      var proposal = suggestion.proposal();
      try {
        CategoryProposalRules.validateNewPath(proposal.parentId(), proposal.names(), tree);
        return true;
      } catch (ApiException invalidPath) {
        return false;
      }
    }
    return hasNode(tree, suggestion.rootCategoryId(), null, 1)
        && hasNode(tree, suggestion.sectionCategoryId(), suggestion.rootCategoryId(), 2)
        && hasNode(tree, suggestion.leafCategoryId(), suggestion.sectionCategoryId(), 3);
  }

  private static boolean hasNode(List<Category> tree, String id, String parentId, int level) {
    return tree.stream().anyMatch(node -> node.id().equals(id)
        && node.level() == level && Objects.equals(node.parentId(), parentId));
  }
}
