package com.nearapp.aicatalogbackend;

import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.nearapp.aicatalogbackend.storage.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class AiCatalogBackendApplicationTests {
  @MockitoBean FirebaseApp app;
  @MockitoBean FirebaseAuth auth;
  @MockitoBean Firestore firestore;
  @MockitoBean FileStorageService storage;

  @Test
  void contextLoadsWithoutCredentials() {}
}
