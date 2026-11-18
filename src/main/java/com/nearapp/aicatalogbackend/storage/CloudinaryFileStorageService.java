package com.nearapp.aicatalogbackend.storage;

import com.cloudinary.Cloudinary;
import com.nearapp.aicatalogbackend.exception.ApiException;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "cloudinary", matchIfMissing = true)
public class CloudinaryFileStorageService implements FileStorageService {
  private final Cloudinary client;
  private final ImageValidator validator;

  public CloudinaryFileStorageService(
      @Value("${cloudinary.cloud-name:}") String name,
      @Value("${cloudinary.api-key:}") String key,
      @Value("${cloudinary.api-secret:}") String secret,
      ImageValidator validator) {
    this.validator = validator;
    if (name.isBlank() || key.isBlank() || secret.isBlank())
      throw new IllegalStateException(
          "Cloudinary requires CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY and"
              + " CLOUDINARY_API_SECRET");
    client =
        new Cloudinary(
            Map.of("cloud_name", name, "api_key", key, "api_secret", secret, "secure", true));
  }

  public StoredFile upload(String productId, MultipartFile file) {
    return uploadAsset("products", productId, file);
  }

  public StoredFile uploadCategory(String categoryId, MultipartFile file) {
    return uploadAsset("categories", categoryId, file);
  }

  private StoredFile uploadAsset(String namespace, String productId, MultipartFile file) {
    validator.validate(file);
    if (!productId.matches("[A-Za-z0-9_-]{1,100}"))
      throw ApiException.bad("Invalid product identifier");
    try {
      var result =
          client
              .uploader()
              .upload(
                  file.getBytes(),
                  Map.of(
                      "public_id",
                      "ai-catalog/" + namespace + "/" + productId + "/" + UUID.randomUUID(),
                      "resource_type",
                      "image",
                      "overwrite",
                      false));
      return new StoredFile((String) result.get("secure_url"), (String) result.get("public_id"));
    } catch (IOException e) {
      throw ApiException.external("Image upload failed");
    }
  }

  public void delete(String key) {
    if (key == null) return;
    if (!key.startsWith("ai-catalog/products/") && !key.startsWith("ai-catalog/categories/")) throw ApiException.bad("Unmanaged storage key");
    try {
      var result =
          client.uploader().destroy(key, Map.of("resource_type", "image", "invalidate", true));
      if (!Set.of("ok", "not found").contains(result.get("result")))
        throw ApiException.external("Image deletion failed; retry operation");
    } catch (IOException e) {
      throw ApiException.external("Image deletion failed; retry operation");
    }
  }
}
