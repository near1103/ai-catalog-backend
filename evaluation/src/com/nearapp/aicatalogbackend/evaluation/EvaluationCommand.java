package com.nearapp.aicatalogbackend.evaluation;

import com.nearapp.aicatalogbackend.config.FirebaseConfig;
import com.nearapp.aicatalogbackend.config.GeminiConfig;
import com.nearapp.aicatalogbackend.evaluation.EvaluationCheckpoint.Manifest;
import com.nearapp.aicatalogbackend.evaluation.EvaluationCheckpoint.Settings;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.repository.FirestoreCategoryRepository;
import com.nearapp.aicatalogbackend.repository.FirestoreProductRepository;
import com.nearapp.aicatalogbackend.service.GeminiCategorySuggestionService;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatProperties;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class EvaluationCommand {
  @EnableAutoConfiguration
  @Import({FirebaseConfig.class, FirestoreCategoryRepository.class, FirestoreProductRepository.class,
      GeminiConfig.class, GeminiCategorySuggestionService.class})
  static class CatalogContext {}

  @EnableAutoConfiguration
  @Import({GeminiConfig.class, GeminiCategorySuggestionService.class})
  static class GeminiContext {}

  private static ConfigurableApplicationContext context(boolean live) {
    return new SpringApplicationBuilder(live ? GeminiContext.class : CatalogContext.class)
        .web(WebApplicationType.NONE).logStartupInfo(false).registerShutdownHook(false)
        .run("--spring.main.banner-mode=off", "--spring.ai.retry.max-attempts=1",
            "--spring.ai.model.embedding.text=none",
            "--spring.ai.model.chat=" + (live ? "google-genai" : "none"),
            "--spring.ai.google.genai.chat.use-cached-content=false",
            "--logging.level.root=WARN");
  }

  private record PlanEntry(GeminiEvaluation.Phase phase, int attempt, String productId) {}

  private record Node(String id, String name, String parentId, int level) {
    Category category() { return new Category(id, name, parentId, level, null); }
  }

  public static void main(String[] args) throws Exception {
    String mode = args[0];
    Path dir = Path.of(args[1]);
    var json = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    switch (mode) {
      case "prepare", "prepare-manual", "check" -> prepare(mode, dir, json);
      case "functional" -> {
        Files.createDirectories(dir);
        var offline = offline(dir, args[2], args[3]);
        EvaluationReport.write(dir, offline, parameters("functional"), "не запускалося", List.of(), json);
      }
      case "live", "resume" -> {
        if (!args[2].equals("--live")) throw new IllegalArgumentException("Explicit --live is required");
        try (var channel = FileChannel.open(dir.resolve(".evaluation.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            var lock = channel.tryLock()) {
          if (lock == null) throw new IllegalStateException("This evaluation run is already in use");
          execute(dir, mode.equals("resume"), args, json);
        }
      }
      default -> throw new IllegalArgumentException("Unknown evaluation mode");
    }
  }

  private static void prepare(String mode, Path dir, ObjectMapper json) throws Exception {
    try (var catalogContext = context(false)) {
      var tree = new EvaluationDataset.Tree(catalogContext.getBean(FirestoreCategoryRepository.class).findAll());
      var products = catalogContext.getBean(FirestoreProductRepository.class).findAll();
      var snapshot = EvaluationDataset.fromCatalog(tree, products);
      System.out.printf("Firestore: %d products, %d eligible, %d excluded; %d categories, %d leaves%n",
          products.size(), snapshot.rows().size(), snapshot.excluded().size(), snapshot.categoryCount(), snapshot.leaves());
      if (mode.equals("check")) return;
      Files.createDirectories(dir);
      if (mode.equals("prepare-manual")) {
        var review = Path.of(System.getenv("AI_CATALOG_EVALUATION_DATASET"));
        EvaluationDataset.prepare(dir, review, tree, products, json);
      } else {
        EvaluationDataset.writeCatalog(dir, snapshot, tree, json);
      }
      json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("category-tree.json").toFile(),
          tree.categories().stream().map(c -> new Node(c.id(), c.name(), c.parentId(), c.level())).toList());
      if (mode.equals("prepare")) EvaluationCheckpoint.save(dir.resolve("snapshot.json"), snapshot, json);
    }
  }

  private static EvaluationReport.Offline offline(Path dir, String junit, String exit) throws Exception {
    return EvaluationReport.offline(dir, Path.of(junit), exit.equals("0")
        ? "Maven завершився успішно" : "Maven завершився з помилкою");
  }

  private static Map<String, Object> parameters(String mode) {
    var parameters = new LinkedHashMap<String, Object>();
    parameters.put("Дата запуску (UTC)", Instant.now().toString());
    parameters.put("Режим", mode);
    parameters.put("Java", System.getProperty("java.version"));
    parameters.put("ОС", System.getProperty("os.name") + " " + System.getProperty("os.version")
        + " (" + System.getProperty("os.arch") + ")");
    parameters.put("Spring Boot", SpringBootVersion.getVersion());
    return parameters;
  }

  static String planHash(List<GeminiEvaluation.Job> jobs, ObjectMapper json) {
    return EvaluationCheckpoint.hash(json.writeValueAsBytes(jobs.stream()
        .map(job -> new PlanEntry(job.phase(), job.attempt(), job.row().id())).toList()));
  }

  private static void execute(Path dir, boolean resume, String[] args, ObjectMapper json) throws Exception {
    Path manifestPath = dir.resolve("run-manifest.json");
    if (resume != Files.exists(manifestPath)) {
      throw new IllegalArgumentException(resume ? "No run manifest to resume" : "Use resume for this run");
    }
    Manifest saved = resume ? json.readValue(manifestPath.toFile(), Manifest.class) : null;
    var settings = resume ? saved.settings() : new Settings(Integer.parseInt(args[5]), Integer.parseInt(args[6]),
        Integer.parseInt(args[7]), Boolean.parseBoolean(args[8]), Long.parseLong(args[9]), Integer.parseInt(args[10]));
    if (settings.limit() < 0 || settings.stabilityCount() < 0 || settings.repeats() < 2
        || settings.delayMs() < 1 || settings.maxRequests() < 1) {
      throw new IllegalArgumentException("Invalid evaluation settings");
    }
    byte[] snapshotBytes = Files.readAllBytes(dir.resolve("snapshot.json"));
    byte[] treeBytes = Files.readAllBytes(dir.resolve("category-tree.json"));
    String snapshotHash = EvaluationCheckpoint.hash(snapshotBytes);
    String treeHash = EvaluationCheckpoint.hash(treeBytes);
    String promptHash = EvaluationCheckpoint.hash(GeminiCategorySuggestionService.SYSTEM_INSTRUCTION
        .getBytes(StandardCharsets.UTF_8));
    if (resume && (saved.format() != EvaluationCheckpoint.FORMAT_VERSION || !snapshotHash.equals(saved.snapshotHash())
        || !treeHash.equals(saved.treeHash()) || !promptHash.equals(saved.promptHash()))) {
      throw new IllegalStateException("Snapshot, prompt or checkpoint format changed. Start a new run.");
    }

    var snapshot = json.readValue(snapshotBytes, EvaluationDataset.Catalog.class);
    var tree = new EvaluationDataset.Tree(Arrays.stream(json.readValue(treeBytes, Node[].class))
        .map(Node::category).toList());
    var rows = snapshot.rows().stream().limit(settings.limit() == 0 ? Long.MAX_VALUE : settings.limit()).toList();
    var jobs = GeminiEvaluation.plan(rows, settings.stabilityCount(), settings.repeats(), settings.reorder());
    System.out.printf("Planned Gemini calls: %d (%d baseline, %d stability, %d reordered); cap: %d%n",
        jobs.size(), rows.size(), jobs.stream().filter(j -> j.phase() == GeminiEvaluation.Phase.STABILITY).count(),
        jobs.stream().filter(j -> j.phase() == GeminiEvaluation.Phase.REORDERED).count(), settings.maxRequests());
    var offline = resume ? saved.offline() : offline(dir, args[3], args[4]);
    if (jobs.isEmpty()) throw new IllegalStateException("No eligible products in the Firestore snapshot");
    if (jobs.size() > settings.maxRequests()) throw new IllegalStateException("Plan exceeds -MaxRequests. No calls made.");
    String planHash = planHash(jobs, json);
    if (resume && (saved.plannedRequests() != jobs.size() || !planHash.equals(saved.planHash()))) {
      throw new IllegalStateException("Saved plan changed");
    }

    var checkpoint = new EvaluationCheckpoint(dir, json);
    var results = new ArrayList<GeminiEvaluation.Result>();
    checkpoint.load(jobs, results);
    System.out.printf("Already recorded: %d; remaining: %d%n", results.size(), jobs.size() - results.size());
    var parameters = parameters("live");
    parameters.put("Дата запуску (UTC)", resume ? saved.startedAt() : parameters.get("Дата запуску (UTC)"));
    parameters.put("Товарів у знімку", snapshot.rows().size());
    parameters.put("Виключено товарів", snapshot.excluded().size());
    parameters.put("Категорій у знімку", snapshot.categoryCount());
    parameters.put("Кінцевих категорій", snapshot.leaves());
    parameters.put("Вибрано товарів для запуску", rows.size());
    parameters.put("Заплановано викликів", jobs.size());
    parameters.put("Ліміт викликів", settings.maxRequests());
    parameters.put("Повторів на товар", settings.repeats());
    parameters.put("Метаморфічна перевірка", settings.reorder());
    parameters.put("Пауза між викликами, мс", settings.delayMs());
    parameters.put("Тайм-аут виклику, мс", GeminiConfig.TIMEOUT_MS);
    parameters.put("Спроб на один виклик", 1);
    parameters.put("SHA-256 плану", planHash);
    parameters.put("SHA-256 системної інструкції", promptHash);
    parameters.put("SHA-256 знімка", snapshotHash);
    parameters.put("SHA-256 дерева", treeHash);

    boolean interrupted = false;
    String activeModel = resume ? saved.model() : null;
    String optionsHash = resume ? saved.optionsHash() : null;
    if (results.size() < jobs.size()) {
      try (var geminiContext = context(true)) {
        var options = geminiContext.getBean(GoogleGenAiChatProperties.class).toOptions();
        activeModel = options.getModel();
        if (activeModel == null || !activeModel.matches("[A-Za-z0-9._/-]{1,120}")) {
          throw new IllegalArgumentException("Invalid configured Gemini model");
        }
        optionsHash = EvaluationCheckpoint.hash(json.writeValueAsBytes(List.of(options, GeminiConfig.TIMEOUT_MS, 1)));
        if (resume && (!activeModel.equals(saved.model()) || !optionsHash.equals(saved.optionsHash()))) {
          throw new IllegalStateException("Gemini configuration changed. Restore it or start a new run.");
        }
        geminiContext.getBean(ChatModel.class);
        if (!resume) {
          var manifest = new Manifest(EvaluationCheckpoint.FORMAT_VERSION,
              parameters.get("Дата запуску (UTC)").toString(), settings, snapshotHash, treeHash,
              activeModel, optionsHash, promptHash, planHash, jobs.size(), offline);
          EvaluationCheckpoint.save(manifestPath, manifest, json);
        }
        try {
          checkpoint.execute(jobs, results, tree,
              geminiContext.getBean(GeminiCategorySuggestionService.class), settings.delayMs());
        } catch (InterruptedException stopped) {
          interrupted = true;
        } finally {
          interrupted |= Thread.interrupted();
        }
      }
    }
    parameters.put("Налаштована модель", activeModel);
    parameters.put("SHA-256 налаштувань моделі", optionsHash);
    int recorded = results.size();
    String state = recorded == jobs.size() ? "завершено"
        : interrupted ? "перервано" : "призупинено після " + results.getLast().status();
    for (var job : jobs.subList(recorded, jobs.size())) {
      results.add(GeminiEvaluation.result(job, GeminiEvaluation.Status.NOT_RUN, null, null, null, null));
    }
    GeminiEvaluation.write(dir, results, json);
    EvaluationReport.write(dir, offline, parameters, state, results, json, snapshot.rows(), tree);
    if (interrupted) Thread.currentThread().interrupt();
    if (recorded < jobs.size()) throw new IllegalStateException(state + ". Continue with -Mode resume --live.");
  }
}
