package com.nearapp.aicatalogbackend.controller;

import com.nearapp.aicatalogbackend.dto.CategorySelection;
import com.nearapp.aicatalogbackend.dto.ProductInput;
import com.nearapp.aicatalogbackend.model.Product;
import com.nearapp.aicatalogbackend.model.ProductStatus;
import com.nearapp.aicatalogbackend.service.ProductService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {
  public record CreateSuggestedCategory(String proposalId, List<JsonNode> names) {}

  private final ProductService products;

  public AdminProductController(ProductService products) {
    this.products = products;
  }

  @GetMapping
  public List<Product> list(@RequestParam(required = false) ProductStatus status) {
    return products.list(false).stream()
        .filter(p -> status == null || p.status() == status)
        .toList();
  }

  @GetMapping("/{id}")
  public Product get(@PathVariable String id) {
    return products.get(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Product create(@Valid @RequestBody ProductInput input) {
    return products.create(input);
  }

  @PutMapping("/{id}")
  public Product update(@PathVariable String id, @Valid @RequestBody ProductInput input) {
    return products.update(id, input);
  }

  @PutMapping("/{id}/category")
  public Product category(@PathVariable String id, @Valid @RequestBody CategorySelection input) {
    return products.category(id, input.categoryId());
  }

  @PostMapping("/{id}/ai-suggestion")
  public Product suggest(@PathVariable String id) {
    return products.suggest(id);
  }

  @PostMapping("/{id}/ai-suggestion/accept")
  public Product accept(@PathVariable String id) {
    return products.acceptSuggestion(id);
  }

  @PostMapping("/{id}/ai-suggestion/create-category")
  public Product createCategory(@PathVariable String id, @RequestBody CreateSuggestedCategory input) {
    var names = input.names() == null ? null : input.names().stream()
        .map(name -> name == null ? null : name.stringValue()).toList();
    return products.createSuggestedCategory(id, input.proposalId(), names);
  }

  @DeleteMapping("/{id}/ai-suggestion")
  public Product reject(@PathVariable String id) {
    return products.rejectSuggestion(id);
  }

  @PostMapping("/{id}/publish")
  public Product publish(@PathVariable String id) {
    return products.publish(id);
  }

  @PostMapping("/{id}/deactivate")
  public Product deactivate(@PathVariable String id) {
    return products.deactivate(id);
  }

  @PostMapping(value = "/{id}/images", consumes = "multipart/form-data")
  public Product upload(@PathVariable String id, @RequestPart("file") MultipartFile file) {
    return products.upload(id, file);
  }

  @PutMapping("/{id}/images/{index}/primary")
  public Product primary(@PathVariable String id, @PathVariable int index) {
    return products.primary(id, index);
  }

  @DeleteMapping("/{id}/images/{index}")
  public Product deleteImage(@PathVariable String id, @PathVariable int index) {
    return products.deleteImage(id, index);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String id) {
    products.delete(id);
  }
}
