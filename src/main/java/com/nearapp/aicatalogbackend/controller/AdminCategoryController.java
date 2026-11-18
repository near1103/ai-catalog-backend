package com.nearapp.aicatalogbackend.controller;

import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.service.CategoryService;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/categories")
public class AdminCategoryController {
  private final CategoryService categories;

  public AdminCategoryController(CategoryService categories) {
    this.categories = categories;
  }

  @GetMapping
  public List<Category> list() {
    return categories.list();
  }

  @GetMapping("/{id}")
  public Category get(@PathVariable String id) {
    return categories.get(id);
  }

  @PutMapping("/{id}")
  public Category save(@PathVariable String id, @Valid @RequestBody Category input) {
    return categories.save(id, input);
  }

  @PostMapping(value = "/{id}/image", consumes = "multipart/form-data")
  public Category uploadImage(@PathVariable String id, @RequestPart("file") MultipartFile file) {
    return categories.uploadImage(id, file);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String id) {
    categories.delete(id);
  }
}
