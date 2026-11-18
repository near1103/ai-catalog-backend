package com.nearapp.aicatalogbackend.storage;

import com.nearapp.aicatalogbackend.exception.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local")
public class LocalFileStorageService implements FileStorageService {
  private final Path root;
  private final String baseUrl;
  private final ImageValidator validator;

  public LocalFileStorageService(
      @Value("${app.storage.local-directory:./uploads}") String directory,
      @Value("${app.storage.local-base-url:http://localhost:8080/media}") String url,
      ImageValidator validator) {
    root = Path.of(directory).toAbsolutePath().normalize();
    baseUrl = url;
    this.validator = validator;
  }

  public StoredFile upload(String productId, MultipartFile file) {
    validator.validate(file);
    String ext =
        switch (file.getContentType()) {
          case "image/jpeg" -> "jpg";
          case "image/png" -> "png";
          case "image/gif" -> "gif";
          default -> "webp";
        };
    String key = UUID.randomUUID() + "." + ext;
    try {
      Files.createDirectories(root);
      try (var in = file.getInputStream()) {
        Files.copy(in, root.resolve(key));
      }
      return new StoredFile(baseUrl + "/" + key, key);
    } catch (IOException e) {
      throw ApiException.external("Local upload failed");
    }
  }

  public StoredFile uploadCategory(String categoryId, MultipartFile file) {
    if (!categoryId.matches("[A-Za-z0-9_-]{1,100}"))
      throw ApiException.bad("Invalid category identifier");
    return upload(categoryId, file);
  }

  public void delete(String key) {
    if (key == null) return;
    if (!key.matches("[a-f0-9-]{36}\\.(jpg|png|gif|webp)"))
      throw ApiException.bad("Invalid local storage key");
    Path target = root.resolve(key).normalize();
    if (!target.startsWith(root)) throw ApiException.bad("Invalid storage path");
    try {
      Files.deleteIfExists(target);
    } catch (IOException e) {
      throw ApiException.external("Local deletion failed");
    }
  }
}
