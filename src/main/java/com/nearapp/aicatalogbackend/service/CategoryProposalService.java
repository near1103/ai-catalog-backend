package com.nearapp.aicatalogbackend.service;

import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.AiSuggestion;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.model.CategorySource;
import com.nearapp.aicatalogbackend.model.Product;
import com.nearapp.aicatalogbackend.repository.CategoryProposalRepository;
import com.nearapp.aicatalogbackend.repository.CategoryProposalRepository.CategoryCreation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CategoryProposalService {
  private final ProductService products;
  private final CategoryProposalRepository proposals;

  public CategoryProposalService(ProductService products, CategoryProposalRepository proposals) {
    this.products = products;
    this.proposals = proposals;
  }

  public Product createSuggestedCategory(String id, String proposalId, List<String> names) {
    synchronized (products) {
      return proposals.createCategories(id, (p, tree) -> {
        var suggestion = p.aiSuggestion();
        var proposal = suggestion == null ? null : suggestion.proposal();
        if (proposal == null || suggestion.type() != AiSuggestion.Type.NEW_CATEGORY
            || !proposal.id().equals(proposalId))
          throw ApiException.conflict("Category proposal is no longer current. Generate a new suggestion");

        var normalized = CategoryProposalRules.normalizeProposalNames(names);
        if (normalized.size() != proposal.names().size())
          throw ApiException.bad("Keep the same number of category levels as the proposal");
        CategoryProposalRules.validateNewPath(proposal.parentId(), normalized, tree);

        String parentId = proposal.parentId();
        int level = 4 - normalized.size();
        var created = new ArrayList<Category>();
        for (var name : normalized) {
          String categoryId = UUID.randomUUID().toString();
          created.add(new Category(categoryId, name, parentId, level++, null));
          parentId = categoryId;
        }
        var updated = new Product(p.id(), p.name(), p.description(), p.characteristics(), p.images(),
            parentId, p.status(), null, CategorySource.AI, p.createdAt(), Instant.now());
        return new CategoryCreation(updated, created);
      });
    }
  }
}
