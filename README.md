# AI Catalog Backend

AI Catalog Backend is a REST API for an online store catalog. Store administrators manage products and categories, review category suggestions from Gemini, and publish products for shoppers to browse without signing in.

## Features

- Public access to published products and the category hierarchy.
- Product drafts with names, descriptions, characteristics, and images. Administrators select a leaf category manually or accept a suggestion from Gemini.
- Product and category image uploads through Cloudinary, with a local storage option for development.
- Administrative access secured by Firebase ID tokens with the `admin: true` custom claim.

The application uses Java 25, Spring Boot 4, Spring Security, and Maven. The Firebase Admin SDK handles authentication and Firestore access. Spring AI integrates with Gemini.

## Local setup

1. Install JDK 25 and point `JAVA_HOME` to it.
2. Configure a Firebase project with a default Firestore database. Set `GOOGLE_APPLICATION_CREDENTIALS` to the absolute path of a service account JSON file stored outside the repository. Set `FIREBASE_PROJECT_ID` if it cannot be inferred from the credentials.
3. Configure image storage. For Cloudinary, set `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, and `CLOUDINARY_API_SECRET`. For local storage, set `APP_STORAGE_TYPE=local`.
4. Optionally set `APP_AI_MODEL=google-genai` and `GEMINI_API_KEY` to enable category suggestions. Manual categorization works with AI disabled, which is the default.
5. Start the application with `./mvnw spring-boot:run` on Linux or macOS, or `./mvnw.cmd spring-boot:run` in PowerShell.

Use [.env.example](.env.example) as a configuration reference and set the variables in your terminal or IDE run configuration. The API listens on port 8080 by default. Try `GET http://localhost:8080/api/public/categories` after startup.

## API overview

| Endpoint | Purpose |
| --- | --- |
| `GET /api/public/categories` | Browse the category hierarchy |
| `GET /api/public/products` | List published products |
| `/api/admin/categories` | Manage categories and upload category images |
| `/api/admin/products` | Create drafts, edit products, and manage images |
| `POST /api/admin/products/{id}/ai-suggestion` | Request a Gemini suggestion for an existing category |
| `POST /api/admin/products/{id}/ai-suggestion/accept` | Assign the suggested category |
| `POST /api/admin/products/{id}/publish` | Publish a product with a leaf category and a primary image |

For admin requests, sign in with Firebase Authentication and send the ID token in the `Authorization` header using the Bearer scheme. The included `AdminClaimsCommand` grants the admin claim to a Firebase user by UID. Refresh the user's ID token after granting the claim.

## Tests

Run `./mvnw test` on Linux or macOS, or `./mvnw.cmd test` on Windows. The automated tests use mocks for external services.
