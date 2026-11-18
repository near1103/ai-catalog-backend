package com.nearapp.aicatalogbackend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.cloud.FirestoreClient;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FirebaseConfig {
  public static synchronized FirebaseApp initialize(String projectId) {
    if (!FirebaseApp.getApps().isEmpty()) {
      try {
        return FirebaseApp.getInstance();
      } catch (IllegalStateException ignored) {
      }
    }
    try {
      var builder =
          FirebaseOptions.builder().setCredentials(GoogleCredentials.getApplicationDefault());
      if (projectId != null && !projectId.isBlank()) builder.setProjectId(projectId);
      return FirebaseApp.initializeApp(builder.build());
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "Firebase requires Application Default Credentials. Set GOOGLE_APPLICATION_CREDENTIALS to"
              + " a service account JSON outside git and FIREBASE_PROJECT_ID if needed.");
    }
  }

  @Bean
  FirebaseApp firebaseApp(@Value("${app.firebase.project-id:}") String projectId) {
    return initialize(projectId);
  }

  @Bean
  FirebaseAuth firebaseAuth(FirebaseApp app) {
    return FirebaseAuth.getInstance(app);
  }

  @Bean(destroyMethod = "close")
  Firestore firestore(FirebaseApp app) {
    return FirestoreClient.getFirestore(app);
  }
}
