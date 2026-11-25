package com.nearapp.aicatalogbackend.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.nearapp.aicatalogbackend.model.AiSuggestion;
import com.nearapp.aicatalogbackend.model.Category;
import com.nearapp.aicatalogbackend.model.CategorySource;
import com.nearapp.aicatalogbackend.model.Product;
import com.nearapp.aicatalogbackend.model.ProductStatus;
import com.nearapp.aicatalogbackend.service.GeminiCategorySuggestionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

class EvaluationTests {
  private final ObjectMapper json = new ObjectMapper();
  private final EvaluationDataset.Tree tree = new EvaluationDataset.Tree(List.of(
      new Category("root", "Root", null, 1, null),
      new Category("section", "Section", "root", 2, null),
      new Category("leaf", "Leaf", "section", 3, null),
      new Category("other", "Other", "section", 3, null)));
  private final EvaluationDataset.Row row = new EvaluationDataset.Row("p", "Name, \"quoted\"",
      "Line one\nLine two", Map.of("a", "1", "b", "2"), "root", "section", "leaf", true);

  @Test
  void datasetUsesOnlyApprovedRowsAndPreservesMultilineText(@TempDir Path dir) throws Exception {
    var file = dir.resolve("dataset.csv");
    var unapproved = new EvaluationDataset.Row("q", "Other", "", Map.of(), "root", "section", "other", false);
    EvaluationDataset.write(file, List.of(row, unapproved), json);
    assertEquals(List.of(row), EvaluationDataset.approved(file, tree, json));
    assertEquals(List.of("b", "a"), new ArrayList<>(row.product(true).characteristics().keySet()));
  }

  @Test
  void prepareCountsEligibleProductsAndUsesAssignedCategoryRegardlessOfSourceOrStatus(@TempDir Path dir)
      throws Exception {
    var review = dir.resolve("reviewed.csv");
    EvaluationDataset.prepare(dir, review, tree, List.of(
        product("a", "leaf", CategorySource.AI, ProductStatus.ACTIVE),
        product("b", "leaf", CategorySource.MANUAL, ProductStatus.INACTIVE),
        product("c", "other", null, ProductStatus.DRAFT),
        product("d", null, CategorySource.MANUAL, ProductStatus.DRAFT),
        product("e", "section", null, ProductStatus.DRAFT),
        product("f", "missing", null, ProductStatus.DRAFT)), json);
    var inspection = EvaluationDataset.inspect(review, tree, json);
    assertEquals(2, inspection.candidates());
    assertEquals(0, inspection.approvedCount());
    assertEquals(List.of(), inspection.problems());
    assertEquals(List.of("b", "c"), inspection.rows().stream().map(EvaluationDataset.Row::id).toList());
    assertThrows(IllegalArgumentException.class, inspection::requireApproved);
    var stats = json.readTree(dir.resolve("prepare-summary.json").toFile());
    assertEquals(3, stats.get("eligibleProducts").asInt());
    assertEquals(1, stats.get("productsWithoutCategory").asInt());
    assertEquals(1, stats.get("productsWithUnknownCategory").asInt());
    assertEquals(1, stats.get("productsWithNonLeafCategory").asInt());
    assertTrue(Files.readString(dir.resolve("dataset-coverage.csv")).contains("leaf,Leaf,2,b,REVIEW_REQUIRED"));
  }

  @Test
  void preparePreservesReviewedRowsAndAddsMissingLeavesWithoutOverwritingReviewFile(@TempDir Path dir)
      throws Exception {
    var review = dir.resolve("reviewed.csv");
    EvaluationDataset.write(review, List.of(row), json);
    byte[] original = Files.readAllBytes(review);
    EvaluationDataset.prepare(dir, review, tree, List.of(
        product("new-leaf-product", "leaf", CategorySource.MANUAL, ProductStatus.ACTIVE),
        product("new-other-product", "other", CategorySource.AI, ProductStatus.DRAFT)), json);
    assertArrayEquals(original, Files.readAllBytes(review));
    var merged = EvaluationDataset.inspect(dir.resolve("dataset.csv"), tree, json);
    assertEquals(List.of(row), merged.requireApproved());
    assertEquals("new-other-product", merged.rows().getLast().id());
    assertFalse(merged.rows().getLast().approved());
  }

  @Test
  void readinessRejectsDuplicateIdsAndInvalidReferencePaths(@TempDir Path dir) throws Exception {
    var file = dir.resolve("reviewed.csv");
    EvaluationDataset.write(file, List.of(row,
        new EvaluationDataset.Row("p", "Duplicate", "", Map.of(), "root", "section", "other", true),
        new EvaluationDataset.Row("q", "Wrong path", "", Map.of(), "section", "root", "other", true)), json);
    var inspection = EvaluationDataset.inspect(file, tree, json);
    assertEquals(3, inspection.candidates());
    assertEquals(3, inspection.approvedCount());
    assertEquals(2, inspection.problems().size());
    assertTrue(inspection.problems().getFirst().contains("Duplicate productId"));
    assertTrue(inspection.problems().getLast().contains("Invalid three-level reference path"));
    assertThrows(IllegalArgumentException.class, inspection::requireApproved);
  }

  @Test
  void automaticDatasetIncludesEveryValidProductAndOmitsReferenceFromGeminiInput(@TempDir Path dir)
      throws Exception {
    var data = EvaluationDataset.fromCatalog(tree, List.of(
        product("a", "leaf", CategorySource.MANUAL, ProductStatus.DRAFT),
        product("b", "leaf", CategorySource.AI, ProductStatus.ACTIVE),
        product("c", "other", null, ProductStatus.INACTIVE),
        product("d", null, null, ProductStatus.DRAFT),
        product("e", "missing", null, ProductStatus.DRAFT),
        product("f", "section", null, ProductStatus.DRAFT)));
    assertEquals(List.of("a", "b", "c"), data.rows().stream().map(EvaluationDataset.Row::id).toList());
    assertEquals(List.of("MISSING_CATEGORY", "UNKNOWN_CATEGORY", "NOT_A_LEAF"),
        data.excluded().stream().map(EvaluationDataset.Excluded::reason).toList());
    assertEquals("AI", data.rows().get(1).categorySource());
    var input = data.rows().get(1).product(false);
    assertNull(input.categoryId());
    assertNull(input.categorySource());
    assertNull(input.aiSuggestion());
    var plan = GeminiEvaluation.plan(data.rows(), 10, 3, false);
    assertEquals(9, plan.size());
    assertEquals(2, plan.stream().filter(j -> j.phase() == GeminiEvaluation.Phase.STABILITY)
        .map(j -> j.row().leaf()).distinct().count());
    EvaluationDataset.writeCatalog(dir, data, tree, json);
    assertEquals(4, Files.readAllLines(dir.resolve("dataset.csv")).size());
    assertEquals(4, Files.readAllLines(dir.resolve("dataset-excluded.csv")).size());
  }

  @Test
  void checkpointResumesRemainingRequestsAndKeepsReservedAndCompletedCalls(@TempDir Path dir) throws Exception {
    var jobs = GeminiEvaluation.plan(List.of(row), 1, 3, true);
    var checkpoint = new EvaluationCheckpoint(dir, json);
    var mockService = mock(GeminiCategorySuggestionService.class);
    var answer = new GeminiCategorySuggestionService.SuggestionResponse(
        new AiSuggestion("root", "section", "leaf", "Reason", null), "gemini-fixture", 10, 3, 13);
    when(mockService.suggestWithMetadata(any(), any()))
        .thenReturn(answer).thenThrow(new IllegalStateException("simulated exit before recording"));
    var partial = new ArrayList<GeminiEvaluation.Result>();
    assertThrows(IllegalStateException.class, () -> checkpoint.execute(jobs, partial, tree, mockService, 1));
    assertEquals(2, partial.size());
    assertTrue(partial.getFirst().valid());
    assertEquals(GeminiEvaluation.Status.INTERRUPTED_UNKNOWN, partial.getLast().status());

    var afterRestart = new EvaluationCheckpoint(dir, json);
    var recovered = new ArrayList<GeminiEvaluation.Result>();
    afterRestart.load(jobs, recovered);
    assertEquals(partial, recovered);
    reset(mockService);
    when(mockService.suggestWithMetadata(any(), any())).thenReturn(answer);
    afterRestart.execute(jobs, recovered, tree, mockService, 1);
    verify(mockService, times(3)).suggestWithMetadata(any(), any());
    assertEquals(5, recovered.size());
    assertEquals(13, recovered.getFirst().totalTokens());
    GeminiEvaluation.write(dir, recovered, json);
    assertEquals(5, Files.readAllLines(dir.resolve("token-usage.csv")).size());
    assertEquals(2, Files.readAllLines(dir.resolve("metamorphic-results.csv")).size());
  }

  @Test
  void completedRunCanRebuildReportsFromItsSnapshotAndCheckpoints(@TempDir Path dir) throws Exception {
    var settings = new EvaluationCheckpoint.Settings(0, 0, 3, false, 1, 1);
    var data = new EvaluationDataset.Catalog(List.of(row), List.of(), 3, 1);
    var snapshot = dir.resolve("snapshot.json");
    EvaluationCheckpoint.save(snapshot, data, json);
    var treePath = dir.resolve("category-tree.json");
    Files.writeString(treePath, """
        [{"id":"root","name":"Root","parentId":null,"level":1},
         {"id":"section","name":"Section","parentId":"root","level":2},
         {"id":"leaf","name":"Leaf","parentId":"section","level":3}]
        """);
    var jobs = GeminiEvaluation.plan(List.of(row), 0, 3, false);
    var planHash = EvaluationCommand.planHash(jobs, json);
    var promptHash = EvaluationCheckpoint.hash(GeminiCategorySuggestionService.SYSTEM_INSTRUCTION
        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var offline = EvaluationReport.offline(dir, null, "fixture");
    EvaluationCheckpoint.save(dir.resolve("run-manifest.json"),
        new EvaluationCheckpoint.Manifest(EvaluationCheckpoint.FORMAT_VERSION, "2026-01-01T00:00:00Z",
            settings, EvaluationCheckpoint.hash(Files.readAllBytes(snapshot)),
            EvaluationCheckpoint.hash(Files.readAllBytes(treePath)), "gemini-fixture", "fixture", promptHash,
            planHash, 1, offline), json);
    var mockService = mock(GeminiCategorySuggestionService.class);
    when(mockService.suggestWithMetadata(any(), any())).thenReturn(new GeminiCategorySuggestionService.SuggestionResponse(
        new AiSuggestion("root", "section", "leaf", "Fixture", null), "gemini-fixture", null, null, null));
    var recorded = new ArrayList<GeminiEvaluation.Result>();
    new EvaluationCheckpoint(dir, json).execute(jobs, recorded, tree, mockService, 1);
    EvaluationCommand.main(new String[] {"resume", dir.toString(), "--live"});
    assertEquals(1, json.readTree(dir.resolve("summary.json").toFile()).at("/accuracy/correctPaths").asInt());
    assertTrue(Files.readString(dir.resolve("report.md")).contains("Таблиця 3"));
    Files.writeString(treePath, "[]");
    assertThrows(IllegalStateException.class,
        () -> EvaluationCommand.main(new String[] {"resume", dir.toString(), "--live"}));
  }

  @Test
  void serializesModelOptionsForResumeVerification() {
    var properties = new org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatProperties();
    properties.setModel("gemini-test");
    var original = EvaluationCheckpoint.hash(json.writeValueAsBytes(properties.toOptions()));
    properties.setTemperature(0.2);
    assertNotEquals(original, EvaluationCheckpoint.hash(json.writeValueAsBytes(properties.toOptions())));
  }

  private static Product product(String id, String category, CategorySource source, ProductStatus status) {
    return new Product(id, id, null, Map.of(), List.of(), category, status, null, source, null, null);
  }

  @Test
  void reportKeepsFailedAndUnrunCallsSeparateFromWrongCategories(@TempDir Path dir) throws Exception {
    var other = new EvaluationDataset.Row("q", "Other", "", Map.of(), "root", "section", "other", true);
    var jobs = GeminiEvaluation.plan(List.of(row, other), 1, 3, true);
    var results = new ArrayList<GeminiEvaluation.Result>();
    for (var job : jobs) {
      var status = job.phase() == GeminiEvaluation.Phase.ACCURACY
          ? job.row().equals(row) ? GeminiEvaluation.Status.VALID : GeminiEvaluation.Status.QUOTA_EXCEEDED
          : GeminiEvaluation.Status.NOT_RUN;
      var answer = status == GeminiEvaluation.Status.VALID
          ? new AiSuggestion("root", "section", "leaf", "Reason", null) : null;
      results.add(GeminiEvaluation.result(job, status, answer, "gemini-test", null,
          status == GeminiEvaluation.Status.NOT_RUN ? null : 15000.0));
    }
    var offline = EvaluationReport.offline(dir, null, "не запускалося");
    GeminiEvaluation.write(dir, results, json);
    EvaluationReport.write(dir, offline, Map.of(), "зупинено після HTTP 429", results, json);
    var summary = json.readTree(dir.resolve("summary.json").toFile());
    assertEquals(1.0, summary.at("/accuracy/top1AccuracyValid").asDouble());
    assertEquals(0.5, summary.at("/accuracy/top1AccuracyPlanned").asDouble());
    assertEquals(1, summary.at("/accuracy/apiErrors").asInt());
    assertEquals(0, summary.at("/accuracy/wrongLeafAmongValid").asInt());
    assertEquals(1, summary.at("/stability/incompleteGroups").asInt());
    assertTrue(summary.at("/latency/requirementMet").asBoolean());
    assertEquals(15, summary.at("/latency/p95Seconds").asInt());
    assertEquals(2, json.readTree(dir.resolve("accuracy-results.json").toFile()).size());
    assertEquals(3, Files.readAllLines(dir.resolve("accuracy-results.csv")).size());
    assertEquals(4, Files.readAllLines(dir.resolve("stability-results.csv")).size());
    assertEquals(2, Files.readAllLines(dir.resolve("metamorphic-results.csv")).size());
    assertEquals(3, Files.readAllLines(dir.resolve("latency-results.csv")).size());
    var markdown = Files.readString(dir.resolve("report.md"));
    assertTrue(markdown.contains("Top-1 Accuracy від плану | 50.00%"));
    assertTrue(markdown.contains("зупинено після HTTP 429"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void quotaFromSpringAiStopsThePlanAfterFirstCall(@TempDir Path dir) throws Exception {
    ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
    var model = mock(ChatModel.class);
    when(provider.getIfAvailable()).thenReturn(model);
    when(model.getOptions()).thenReturn(ChatOptions.builder().build());
    var quota = new com.google.genai.errors.ApiException(429, "RESOURCE_EXHAUSTED", "test response");
    when(model.call(any(Prompt.class))).thenThrow(quota);
    var results = new ArrayList<GeminiEvaluation.Result>();
    new EvaluationCheckpoint(dir, json).execute(GeminiEvaluation.plan(List.of(row), 1, 3, true), results, tree,
        new GeminiCategorySuggestionService(provider, json), 1);
    assertEquals(1, results.size());
    assertEquals(GeminiEvaluation.Status.QUOTA_EXCEEDED, results.getFirst().status());
    assertEquals(429, results.getFirst().httpStatus());
    verify(model).call(any(Prompt.class));
  }
}
