package com.nearapp.aicatalogbackend;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.nearapp.aicatalogbackend.config.FirebaseConfig;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class FirebaseConfigurationTests {
  @Test
  void reusesExistingDefaultAppWithoutReadingCredentials() {
    try (var apps = mockStatic(FirebaseApp.class);
        var credentials = mockStatic(GoogleCredentials.class)) {
      var app = mock(FirebaseApp.class);
      apps.when(FirebaseApp::getApps).thenReturn(List.of(app));
      apps.when(FirebaseApp::getInstance).thenReturn(app);
      assertSame(app, FirebaseConfig.initialize("project"));
      assertSame(app, FirebaseConfig.initialize("project"));
      credentials.verifyNoInteractions();
    }
  }

  @Test
  void missingCredentialsHaveHelpfulMessageWithoutContents() {
    try (var apps = mockStatic(FirebaseApp.class);
        var credentials = mockStatic(GoogleCredentials.class)) {
      apps.when(FirebaseApp::getApps).thenReturn(List.of());
      credentials
          .when(GoogleCredentials::getApplicationDefault)
          .thenThrow(new IOException("sensitive contents"));
      var error =
          assertThrows(IllegalStateException.class, () -> FirebaseConfig.initialize("project"));
      assertTrue(error.getMessage().contains("GOOGLE_APPLICATION_CREDENTIALS"));
      assertFalse(error.getMessage().contains("sensitive contents"));
      assertNull(error.getCause());
    }
  }
}
