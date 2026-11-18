package com.nearapp.aicatalogbackend.storage;

import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {
  record StoredFile(String url, String storageKey) {}

  StoredFile upload(String productId, MultipartFile file);

  StoredFile uploadCategory(String categoryId, MultipartFile file);

  void delete(String storageKey);
}
