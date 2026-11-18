package com.nearapp.aicatalogbackend;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.storage.ImageValidator;
import com.nearapp.aicatalogbackend.storage.LocalFileStorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class StorageTests {
  @TempDir Path directory;
  byte[] png = new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

  @Test
  void rejectsEmpty() {
    assertThrows(
        ApiException.class,
        () ->
            new ImageValidator(100)
                .validate(new MockMultipartFile("file", "x.png", "image/png", new byte[0])));
  }

  @Test
  void rejectsOversize() {
    assertThrows(
        ApiException.class,
        () ->
            new ImageValidator(4)
                .validate(new MockMultipartFile("file", "x.png", "image/png", png)));
  }

  @Test
  void rejectsUnsupported() {
    assertThrows(
        ApiException.class,
        () ->
            new ImageValidator(100)
                .validate(new MockMultipartFile("file", "x.svg", "image/svg+xml", png)));
  }

  @Test
  void rejectsSpoofedType() {
    assertThrows(
        ApiException.class,
        () ->
            new ImageValidator(100)
                .validate(
                    new MockMultipartFile(
                        "file", "x.png", "image/png", "not an image".getBytes())));
  }

  @Test
  void localUploadUsesSafeNameAndCanBeDeleted() throws Exception {
    var storage =
        new LocalFileStorageService(
            directory.toString(), "http://localhost/media", new ImageValidator(100));
    var uploaded =
        storage.upload("p", new MockMultipartFile("file", "../../evil.png", "image/png", png));
    assertTrue(Files.exists(directory.resolve(uploaded.storageKey())));
    assertFalse(uploaded.storageKey().contains("evil"));
    storage.delete(uploaded.storageKey());
    assertFalse(Files.exists(directory.resolve(uploaded.storageKey())));
  }

  @Test
  void localRejectsTraversal() {
    var storage =
        new LocalFileStorageService(
            directory.toString(), "http://localhost/media", new ImageValidator(100));
    assertThrows(ApiException.class, () -> storage.delete("../outside"));
  }
}
