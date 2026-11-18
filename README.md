# AI Catalog Backend

Java 25, Spring Boot 4.1.1, Maven, Firebase Admin / Firestore / Authentication,
Cloudinary, Spring AI 2.0.1 / Gemini. Frontend — отдельный REST-клиент.

## Реализация

В исходном проекте были bootstrap и JSON seed, без реализованных backend phases.
Добавлены domain → repository → service → controller, публичный каталог, admin CRUD,
публикация, изображения, Firebase ID tokens и AI suggestions. Firestore API изолирован
в repository и инфраструктурном setup/config коде. В Product нет categoryPath.
Публичный DTO не содержит storageKey и внутренних AI данных.

## Секреты и конфигурация

Ранее находившийся в application.yaml Gemini key удалён: отзовите его и создайте новый.
Нельзя помещать реальные credentials в исходники, YAML, frontend или git.
`.env.example` содержит только placeholders. Spring Boot не загружает `.env` автоматически;
задайте переменные в ОС или конфигурации запуска IDE. Dotenv dependency отсутствует.
Для внешнего local config можно использовать `SPRING_CONFIG_ADDITIONAL_LOCATION`.
Service account JSON и локальный конфиг с секретами храните вне repository.

| Переменная | Назначение |
| --- | --- |
| GOOGLE_APPLICATION_CREDENTIALS | Абсолютный путь к service account JSON вне git |
| FIREBASE_PROJECT_ID | Firebase project ID; опционально, если определяется ADC |
| CLOUDINARY_CLOUD_NAME | Cloud name |
| CLOUDINARY_API_KEY | API key |
| CLOUDINARY_API_SECRET | API secret, только backend |
| APP_STORAGE_TYPE | `cloudinary` (default) или `local` |
| GEMINI_API_KEY | Gemini Developer API key |
| APP_AI_MODEL | `google-genai` включает AI; default `none` |
| GEMINI_MODEL | Default `gemini-2.5-flash` |
| APP_UPLOAD_MAX_BYTES | Проверка адаптером; default 10485760 |
| APP_UPLOAD_MAX_SIZE | Multipart file limit; default 10MB |
| APP_UPLOAD_MAX_REQUEST_SIZE | Multipart request limit; default 11MB |
| APP_CORS_ALLOWED_ORIGINS | Origins frontend через запятую; default пусто |
| APP_STORAGE_LOCAL_DIRECTORY | Только local; default `./uploads` |
| APP_STORAGE_LOCAL_BASE_URL | Только local; default `http://localhost:8080/media` |

AI выключен по умолчанию, чтобы отсутствие Gemini не мешало ручной работе.
Для включения задайте GEMINI_API_KEY и APP_AI_MODEL=google-genai.
Проверены свойства установленного Spring AI 2.0.1: `spring.ai.google.genai.api-key`
и `spring.ai.google.genai.chat.model`.
Firebase и выбранный storage обязательны для запуска сервера; ошибки конфигурации
останавливают startup с пояснением. Тесты подменяют внешние зависимости.

## Firebase service account

1. Откройте существующий Firebase / Google Cloud project.
2. Создайте Firestore database `(default)`, если её ещё нет.
3. Firebase Console → Project settings → Service accounts → Firebase Admin SDK → Generate new private key.
   В Google Cloud: IAM & Admin → Service Accounts → backend account → Keys.
4. Сохраните JSON вне git, например `C:/Users/you/.secrets/firebase-service-account.json`.
5. Установите GOOGLE_APPLICATION_CREDENTIALS на этот абсолютный путь.
6. При необходимости укажите FIREBASE_PROJECT_ID.
7. Предоставьте service account права Firestore и Firebase Authentication:
   чтение пользователей для revoked-token проверки и назначение custom claims через CLI.
8. Запустите backend командой ниже.

Service account JSON содержит private key: это секрет, его нельзя коммитить или отдавать frontend.
Backend использует ADC, один default FirebaseApp, FirebaseAuth и Firestore.
Firebase client SDK на backend отсутствует. Admin SDK работает через IAM и обходит Firestore Rules.
Если клиент работает с каталогом только через backend, запретите прямой доступ client SDK:

```text
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /{document=**} { allow read, write: if false; }
  }
}
```

Коллекции: `categories/{categoryId}`, `products/{productId}`. Domain Instant сохраняются
как ISO-8601 UTC; reader также поддерживает нативные Firestore Timestamp.

## Назначение ADMIN

Используйте существующего Firebase Authentication user. Единственный admin claim:
**`admin: true` (boolean)**. Строка `admin: "true"`, `role: ADMIN`, body, query,
клиентские headers и Firestore документы роль не дают.

CLI не запускает web server и не требует Cloudinary/Gemini. В PowerShell, с JAVA_HOME
на JDK 25 и Firebase environment:

```powershell
.\mvnw.cmd -q -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=target/runtime-classpath.txt"
$catalogClasspath = "target/classes;" + (Get-Content target/runtime-classpath.txt -Raw).Trim()
& "$env:JAVA_HOME/bin/java.exe" -cp $catalogClasspath com.nearapp.aicatalogbackend.setup.AdminClaimsCommand --uid YOUR_FIREBASE_UID
# Альтернатива: --email admin@example.com
```

CLI сохраняет остальные custom claims и добавляет admin=true. REST endpoint назначения роли нет.
После назначения войдите снова или вызовите `getIdToken(true)` на frontend.
Отправляйте `Authorization: Bearer <FIREBASE_ID_TOKEN>`. Проверяется ID token с проверкой
revoked tokens и disabled users. Custom token и Google access token не подходят.

## Cloudinary

1. В Cloudinary Dashboard возьмите Cloud name, API key, API secret.
2. Установите CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY, CLOUDINARY_API_SECRET.
3. APP_STORAGE_TYPE=cloudinary (default); дублировать credentials в CLOUDINARY_URL не нужно.
4. API secret хранится только на backend; frontend загружает изображение через backend.
5. Создайте DRAFT через API и проверьте multipart upload с полем `file`:

```powershell
curl.exe -X POST http://localhost:8080/api/admin/products/PRODUCT_ID/images -H "Authorization: Bearer YOUR_ID_TOKEN" -F "file=@C:/images/product.jpg"
```

FileStorageService использует официальный SDK через CloudinaryFileStorageService.
Результат: secure URL и public_id как storageKey. Ключ генерируется backend:
`ai-catalog/products/{productId}/{UUID}`. Исходное имя файла не является storage path.
Поддерживаются непустые JPEG, PNG, WebP, GIF: проверяются media type, сигнатура и размер.
Полного декодирования и antivirus нет.

Для альтернативного local provider задайте APP_STORAGE_TYPE=local; Cloudinary credentials
тогда не нужны. Файлы размещаются в APP_STORAGE_LOCAL_DIRECTORY и доступны через `/media/{key}`.
Local provider предназначен для разработки. Известные delivery URLs доступны напрямую,
включая изображения drafts; это относится и к обычным Cloudinary delivery URLs.
Не меняйте provider при наличии managed images без миграции assets и storageKey.

## Правила изображений и удаления

ProductImage содержит url, storageKey, isPrimary. Максимум одна primary; первая загрузка
автоматически primary. Назначение новой primary сбрасывает прежнюю. При удалении primary
выбирается первая оставшаяся картинка. ACTIVE требует leaf category и изображения
с ровно одной primary. Удалять последнюю картинку ACTIVE нельзя: сначала deactivate.
При удалении другой картинки ACTIVE-товар сначала становится INACTIVE и остаётся таким:
проверьте результат и опубликуйте его повторно.

Удаление товара: сохранить INACTIVE → удалить managed assets → удалить документ.
Ошибка провайдера возвращает 502, сохраняет документ с ключами и логирует диагностику без
credentials. Повторите DELETE после восстановления провайдера; `not found` считается успехом.
При ошибке БД после upload выполняется компенсационное удаление; если оно не удалось,
ключ asset логируется для ручной очистки.

Firestore и Cloudinary не имеют общей транзакции. При сбое БД после удаления asset документ
может временно ссылаться на отсутствующий asset; предварительная деактивация скрывает товар.
Повторите операцию. Фонового cleanup worker нет.

`storageKey == null` означает external/unmanaged image: удаляется только ссылка.
Существующие seed URL не перезагружаются и не удаляются из Cloudinary.

## Gemini

Запускается admin вручную: один товар → один запрос Spring AI / Gemini.
Повторные попытки отключены в Spring AI и SDK; SDK timeout — 60 секунд.
Передаются name, description, characteristics и дерево категорий, без images.
Structured response: rootCategoryId, sectionCategoryId, leafCategoryId, reason.
Проверяются существование IDs, уровни 1 → 2 → 3 и parent links.
Suggestion сохраняется отдельно; accept устанавливает categorySource=AI и leaf category.
Ручной выбор устанавливает categorySource=MANUAL; reject удаляет suggestion.
Изменение name, description или characteristics сбрасывает suggestion; images — нет.
AI failure возвращает 502, ручная категоризация и публикация остаются доступны.

Если Google возвращает `400 API_KEY_INVALID`, проверьте `GEMINI_API_KEY`:
нужен действующий ключ Gemini Developer API из Google AI Studio. Firebase web API key
и service account JSON его не заменяют. После изменения `.env` перезапустите backend
с подключённым env-файлом: работающий процесс не перечитывает его автоматически.
`APP_AI_MODEL=google-genai`, `GEMINI_MODEL=gemini-2.5-flash`.
Если API сообщает, что модель недоступна этому ключу, выберите доступную модель;
для текущего окружения проверена `GEMINI_MODEL=gemini-3.8-flash`.
SDK client получает ключ через `GoogleGenAiConnectionProperties`; тест проверяет
фактический `Client.apiKey()`, чтобы исключить передачу неразрешённого placeholder.
При ошибке сервис пишет типы исключений, HTTP-код Google SDK и stack trace цепочки причин.
Тексты исключений исключены из этого лога: SDK и JSON parser могут включать в них
ключи, входные данные или ответ модели. Публичный ProblemDetail остаётся прежним.

## REST API

Public операции доступны гостю и read-only. Только ACTIVE товары; draft/inactive по ID → 404.
Admin: без токена → 401, verified token без admin claim → 403, плохой токен → 401.
Ошибки представлены как ProblemDetail без внутренних исключений и credentials.

| Method | Path | Действие |
| --- | --- | --- |
| GET | /api/public/categories | Категории |
| GET | /api/public/categories/{id} | Категория |
| GET | /api/public/products | ACTIVE товары |
| GET | /api/public/products/{id} | Публичная карточка |
| GET | /api/admin/categories | Все категории |
| GET | /api/admin/categories/{id} | Категория |
| PUT | /api/admin/categories/{id} | Создать/обновить |
| POST | /api/admin/categories/{id}/image | Multipart category image, field `file`; replaces the current image |
| DELETE | /api/admin/categories/{id} | Удалить, если нет children/products |
| GET | /api/admin/products?status=DRAFT | Все товары / фильтр DRAFT, ACTIVE, INACTIVE |
| GET | /api/admin/products/{id} | Admin-карточка |
| POST | /api/admin/products | Создать DRAFT (201) |
| PUT | /api/admin/products/{id} | Обновить name/description/characteristics |
| PUT | /api/admin/products/{id}/category | Ручной выбор: `{"categoryId":"leaf-id"}` |
| POST | /api/admin/products/{id}/ai-suggestion | Получить suggestion |
| POST | /api/admin/products/{id}/ai-suggestion/accept | Принять |
| DELETE | /api/admin/products/{id}/ai-suggestion | Отклонить |
| POST | /api/admin/products/{id}/publish | Опубликовать |
| POST | /api/admin/products/{id}/deactivate | Деактивировать |
| POST | /api/admin/products/{id}/images | Multipart, поле file |
| PUT | /api/admin/products/{id}/images/{index}/primary | Назначить primary |
| DELETE | /api/admin/products/{id}/images/{index} | Удалить изображение |
| DELETE | /api/admin/products/{id} | Удалить товар и managed assets (204) |

Индексы images начинаются с 0: перечитывайте массив после изменений.
Вход создания/обновления товара:

```json
{"name":"Phone", "description":"A phone", "characteristics":{"color":"black"}}
```

Категория для PUT (id в body можно опустить):

```json
{"name":"Smartphones", "parentId":"phones-wearables", "level":3, "imageUrl":null}
```

Root: parentId=null, level=1; section: level=2; leaf: level=3. Иерархия существующей
категории неизменяема, name/imageUrl редактируются. Для переноса создайте новую
категорию и переназначьте товары — это сохраняет валидность путей опубликованных товаров.
Текущий вариант рассчитан на один backend instance с последовательными admin изменениями.
Перед multi-instance deployment потребуются Firestore concurrency controls.
Списки пока возвращаются целиком; pagination можно добавить при росте данных.

## Seed import

Используются `data/catalog-seed/categories.json` и `products.json`.
`image-candidates.json` содержит кандидатов; выбранные URL уже находятся в products.json.
Импорт явный, не запускается при startup; существующие IDs пропускаются.
categoryPath не переносится в domain; imageUrl → ProductImage(url, null, true).
Товары импортируются как DRAFT с MANUAL category source для проверки перед публикацией.

```powershell
# Подготовьте classpath как в разделе ADMIN.
& "$env:JAVA_HOME/bin/java.exe" -cp $catalogClasspath com.nearapp.aicatalogbackend.setup.SeedImportCommand data/catalog-seed
```

## Запуск и проверки

Установите JAVA_HOME на JDK 25 и environment из `.env.example`:

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
# Или после сборки:
& "$env:JAVA_HOME/bin/java.exe" -jar target/ai-catalog-backend-0.0.1-SNAPSHOT.jar
```

В Linux/macOS используйте `./mvnw` и `:` вместо `;` в ручном classpath.
Сервер слушает 8080. Unit/MockMvc/context tests используют mocks внешних сервисов.
Live Firebase/Cloudinary/Gemini integration проверяется после установки credentials.

## Что осталось frontend

Firebase client SDK login (Email/Password, Google), token refresh и Bearer ID token;
публичный каталог; admin CRUD/drafts; ручная категория и AI accept/reject;
multipart upload через backend, primary image, publish/deactivate.
Регистрация для просмотра каталога не обязательна. Service account и Cloudinary API secret
не передаются frontend. Для отдельного origin задайте APP_CORS_ALLOWED_ORIGINS.

Официальные справочники: [Firebase Admin setup](https://firebase.google.com/docs/admin/setup),
[custom claims](https://firebase.google.com/docs/auth/admin/custom-claims),
[Cloudinary Java SDK](https://cloudinary.com/documentation/java_integration),
[Spring AI Google GenAI](https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html).
