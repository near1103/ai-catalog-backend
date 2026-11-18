package com.nearapp.aicatalogbackend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.cloudinary.Cloudinary;
import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.storage.CloudinaryFileStorageService;
import com.nearapp.aicatalogbackend.storage.ImageValidator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class CloudinaryStorageTests {
  @Test
  void uploadReturnsSecureUrlAndProviderKey() throws Exception {
    try (var clients =
        mockConstruction(Cloudinary.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS))) {
      var storage =
          new CloudinaryFileStorageService(
              "test-cloud", "placeholder-key", "placeholder-secret", new ImageValidator(100));
      var client = clients.constructed().getFirst();
      when(client.uploader().upload(any(byte[].class), anyMap()))
          .thenReturn(
              Map.of(
                  "secure_url", "https://cloud/image", "public_id", "ai-catalog/products/p/key"));
      var result =
          storage.upload(
              "p",
              new MockMultipartFile(
                  "file",
                  "../../name.png",
                  "image/png",
                  new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}));
      assertEquals("https://cloud/image", result.url());
      assertEquals("ai-catalog/products/p/key", result.storageKey());
    }
  }

  @Test
  void notFoundDeletionIsIdempotent() throws Exception {
    try (var clients =
        mockConstruction(Cloudinary.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS))) {
      var storage =
          new CloudinaryFileStorageService(
              "test-cloud", "placeholder-key", "placeholder-secret", new ImageValidator(100));
      when(clients.constructed().getFirst().uploader().destroy(anyString(), anyMap()))
          .thenReturn(Map.of("result", "not found"));
      assertDoesNotThrow(() -> storage.delete("ai-catalog/products/p/key"));
    }
  }

  @Test
  void providerDeleteErrorIsNotSilentlyIgnored() throws Exception {
    try (var clients =
        mockConstruction(Cloudinary.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS))) {
      var storage =
          new CloudinaryFileStorageService(
              "test-cloud", "placeholder-key", "placeholder-secret", new ImageValidator(100));
      when(clients.constructed().getFirst().uploader().destroy(anyString(), anyMap()))
          .thenReturn(Map.of("result", "error"));
      assertThrows(ApiException.class, () -> storage.delete("ai-catalog/products/p/key"));
    }
  }

  @Test
  void missingCredentialsFailsClearly() {
    assertThrows(
        IllegalStateException.class,
        () -> new CloudinaryFileStorageService("", "", "", new ImageValidator(100)));
  }
}
