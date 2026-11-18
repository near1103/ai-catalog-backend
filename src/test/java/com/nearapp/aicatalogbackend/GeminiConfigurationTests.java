package com.nearapp.aicatalogbackend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.genai.Client;
import com.nearapp.aicatalogbackend.storage.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    properties = {
      "spring.ai.model.chat=google-genai",
      "spring.ai.google.genai.api-key=placeholder-not-a-real-key"
    })
class GeminiConfigurationTests {
  @MockitoBean FirebaseApp app;
  @MockitoBean FirebaseAuth auth;
  @MockitoBean Firestore firestore;
  @MockitoBean FileStorageService storage;
  @Autowired GoogleGenAiChatModel model;
  @Autowired Client client;

  @Test
  void enabledGeminiBuildsWithoutNetworkAccess() {
    assertEquals("gemini-2.5-flash", model.getOptions().getModel());
    assertEquals("placeholder-not-a-real-key", client.apiKey());
  }
}
