package com.nearapp.aicatalogbackend;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nearapp.aicatalogbackend.config.SecurityConfig;
import com.nearapp.aicatalogbackend.controller.AdminCategoryController;
import com.nearapp.aicatalogbackend.controller.AdminProductController;
import com.nearapp.aicatalogbackend.controller.PublicCatalogController;
import com.nearapp.aicatalogbackend.exception.ApiExceptionHandler;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.security.FirebaseTokenVerifier;
import com.nearapp.aicatalogbackend.service.CategoryService;
import com.nearapp.aicatalogbackend.service.ProductService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    excludeAutoConfiguration =
        UserDetailsServiceAutoConfiguration.class,
    controllers = {
      PublicCatalogController.class,
      AdminProductController.class,
      AdminCategoryController.class
    })
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class SecurityTests {
  @Autowired MockMvc mvc;
  @MockitoBean ProductService products;
  @MockitoBean CategoryService categories;
  @MockitoBean FirebaseTokenVerifier verifier;

  @Test
  void guestCanRead() throws Exception {
    when(products.list(true)).thenReturn(List.of());
    mvc.perform(get("/api/public/products")).andExpect(status().isOk());
    verify(products).list(true);
  }

  @Test
  void guestCannotWrite() throws Exception {
    mvc.perform(post("/api/admin/products").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void spoofedRoleHeaderDoesNotGrantAdmin() throws Exception {
    mvc.perform(get("/api/admin/products").header("role", "ADMIN").header("admin", "true"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void verifiedNonAdminForbidden() throws Exception {
    when(verifier.verify("user")).thenReturn(new FirebaseTokenVerifier.Identity("uid", false));
    mvc.perform(get("/api/admin/products").header("Authorization", "Bearer user"))
        .andExpect(status().isForbidden());
  }

  @Test
  void verifiedAdminAllowed() throws Exception {
    when(verifier.verify("admin")).thenReturn(new FirebaseTokenVerifier.Identity("uid", true));
    when(products.list(false)).thenReturn(List.of());
    mvc.perform(get("/api/admin/products").header("Authorization", "Bearer admin"))
        .andExpect(status().isOk());
  }

  @Test
  void invalidTokenRejected() throws Exception {
    when(verifier.verify("bad")).thenThrow(new IllegalArgumentException());
    mvc.perform(get("/api/admin/products").header("Authorization", "Bearer bad"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void malformedBearerRejected() throws Exception {
    mvc.perform(get("/api/admin/products").header("Authorization", "Bearer "))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void publicWritesDenied() throws Exception {
    mvc.perform(post("/api/public/products")).andExpect(status().isUnauthorized());
  }

  @Test
  void invalidInputReturns400() throws Exception {
    when(verifier.verify("admin")).thenReturn(new FirebaseTokenVerifier.Identity("uid", true));
    mvc.perform(
            post("/api/admin/products")
                .header("Authorization", "Bearer admin")
                .contentType("application/json")
                .content("{\"name\":\"\",\"characteristics\":{}}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void categoryImageUploadReturnsCategoryWithoutInternalKey() throws Exception {
    when(verifier.verify("admin")).thenReturn(new FirebaseTokenVerifier.Identity("uid", true));
    when(categories.uploadImage(eq("c"), any()))
        .thenReturn(new Category("c", "Category", null, 1, "https://example.com/image.png", "private-key"));
    var file = new MockMultipartFile("file", "category.png", "image/png", new byte[] {1});
    mvc.perform(multipart("/api/admin/categories/c/image").file(file).header("Authorization", "Bearer admin"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("c"))
        .andExpect(jsonPath("$.imageUrl").value("https://example.com/image.png"))
        .andExpect(jsonPath("$.imageStorageKey").doesNotExist());
    verify(categories).uploadImage(eq("c"), any());
  }

  @Test
  void categoryImageUploadRequiresAuthentication() throws Exception {
    mvc.perform(multipart("/api/admin/categories/c/image")
        .file(new MockMultipartFile("file", "category.png", "image/png", new byte[] {1})))
        .andExpect(status().isUnauthorized());
    verify(categories, never()).uploadImage(anyString(), any());
  }

  @Test
  void categoryImageUploadRejectsNonAdmin() throws Exception {
    when(verifier.verify("user")).thenReturn(new FirebaseTokenVerifier.Identity("uid", false));
    mvc.perform(multipart("/api/admin/categories/c/image")
        .file(new MockMultipartFile("file", "category.png", "image/png", new byte[] {1}))
        .header("Authorization", "Bearer user"))
        .andExpect(status().isForbidden());
    verify(categories, never()).uploadImage(anyString(), any());
  }

  @Test
  void categoryImageUploadRequiresFilePart() throws Exception {
    when(verifier.verify("admin")).thenReturn(new FirebaseTokenVerifier.Identity("uid", true));
    mvc.perform(multipart("/api/admin/categories/c/image").header("Authorization", "Bearer admin"))
        .andExpect(status().isBadRequest());
    verify(categories, never()).uploadImage(anyString(), any());
  }
}
