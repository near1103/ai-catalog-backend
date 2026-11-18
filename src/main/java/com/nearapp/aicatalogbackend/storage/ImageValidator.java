package com.nearapp.aicatalogbackend.storage;

import com.nearapp.aicatalogbackend.exception.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ImageValidator {
  private final long maxBytes;

  public ImageValidator(@Value("${app.storage.max-bytes:10485760}") long maxBytes) {
    this.maxBytes = maxBytes;
  }

  public void validate(MultipartFile file) {
    if (file.isEmpty()) throw ApiException.bad("Image is empty");
    if (file.getSize() > maxBytes) throw ApiException.bad("Image exceeds upload limit");
    if (!Set.of("image/jpeg", "image/png", "image/webp", "image/gif")
        .contains(file.getContentType() == null ? "" : file.getContentType()))
      throw ApiException.bad("Supported images: JPEG, PNG, WebP, GIF");
    try (var in = file.getInputStream()) {
      byte[] b = in.readNBytes(12);
      String type = file.getContentType();
      boolean valid =
          switch (type) {
            case "image/jpeg" ->
                b.length >= 3 && (b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255;
            case "image/png" ->
                b.length >= 8
                    && Arrays.equals(
                        Arrays.copyOf(b, 8),
                        new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            case "image/gif" ->
                b.length >= 6
                    && new String(b, 0, 6, StandardCharsets.US_ASCII)
                        .matches("GIF8[79]a");
            case "image/webp" ->
                b.length >= 12
                    && new String(b, 0, 4, StandardCharsets.US_ASCII)
                        .equals("RIFF")
                    && new String(b, 8, 4, StandardCharsets.US_ASCII)
                        .equals("WEBP");
            default -> false;
          };
      if (!valid) throw ApiException.bad("Image signature does not match media type");
    } catch (IOException e) {
      throw ApiException.bad("Cannot read image");
    }
  }
}
