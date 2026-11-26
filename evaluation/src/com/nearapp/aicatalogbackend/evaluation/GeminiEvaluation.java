package com.nearapp.aicatalogbackend.evaluation;

import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.AiSuggestion;
import com.nearapp.aicatalogbackend.service.CategoryService;
import com.nearapp.aicatalogbackend.service.GeminiCategorySuggestionService;
import com.nearapp.aicatalogbackend.service.SuggestionResponse;
import com.nearapp.aicatalogbackend.service.GeminiCategorySuggestionService.ProviderStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import tools.jackson.databind.ObjectMapper;

final class GeminiEvaluation {
  enum Phase { ACCURACY, STABILITY, REORDERED }
  enum Status { VALID, INVALID_PATH, INVALID_REASON, API_ERROR, TIMEOUT, RESPONSE_ERROR, QUOTA_EXCEEDED, INTERRUPTED_UNKNOWN, NOT_RUN }

  record Job(Phase phase, int attempt, EvaluationDataset.Row row) {}
  record Result(Phase phase, int attempt, String productId, Status status,
      String expectedRoot, String expectedSection, String expectedLeaf,
      String root, String section, String leaf, String modelVersion,
      Integer httpStatus, Double elapsedMs, boolean rootCorrect, boolean sectionCorrect,
      boolean leafCorrect, String categorySource, String productStatus, String reason,
      Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    boolean correctPath() { return rootCorrect && sectionCorrect && leafCorrect; }
    boolean valid() { return status == Status.VALID; }
    boolean attempted() { return status != Status.NOT_RUN; }
  }

  private static final String[] HEADER = {"phase", "attempt", "productId", "status",
      "expectedRoot", "expectedSection", "expectedLeaf", "root", "section", "leaf",
      "modelVersion", "httpStatus", "elapsedMs", "rootCorrect", "sectionCorrect", "leafCorrect",
      "categorySource", "productStatus", "reason", "promptTokens", "completionTokens", "totalTokens"};

  static List<Job> plan(List<EvaluationDataset.Row> rows, int subset, int repeats, boolean reorder) {
    var jobs = new ArrayList<Job>();
    rows.forEach(row -> jobs.add(new Job(Phase.ACCURACY, 1, row)));
    for (var row : diverseSubset(rows, subset)) {
      for (int i = 1; i <= repeats; i++) jobs.add(new Job(Phase.STABILITY, i, row));
      if (reorder && row.characteristics().size() > 1) jobs.add(new Job(Phase.REORDERED, 1, row));
    }
    return jobs;
  }

  private static List<EvaluationDataset.Row> diverseSubset(List<EvaluationDataset.Row> rows, int limit) {
    var roots = new TreeMap<String, Queue<EvaluationDataset.Row>>();
    var leaves = new HashSet<String>();
    for (var row : rows) {
      if (leaves.add(row.leaf())) roots.computeIfAbsent(row.root(), id -> new ArrayDeque<>()).add(row);
    }
    var selected = new ArrayList<EvaluationDataset.Row>();
    while (!roots.isEmpty() && selected.size() < limit) {
      var iterator = roots.values().iterator();
      while (iterator.hasNext() && selected.size() < limit) {
        var queue = iterator.next();
        selected.add(queue.remove());
        if (queue.isEmpty()) iterator.remove();
      }
    }
    return selected;
  }

  static Result classify(Job job, EvaluationDataset.Tree tree, CategoryService validator,
      GeminiCategorySuggestionService gemini) {
    var product = job.row().product(job.phase() == Phase.REORDERED);
    long start = System.nanoTime();
    SuggestionResponse response;
    try {
      response = gemini.suggestWithMetadata(product, tree.categories());
    } catch (ApiException error) {
      double elapsed = elapsed(start);
      Integer code = null;
      boolean transport = false;
      boolean timeout = false;
      boolean interrupted = Thread.currentThread().isInterrupted();
      for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
        if (cause instanceof ProviderStatus provider) code = provider.code();
        String type = cause.getMessage(); // The service stores only class names in this chain.
        timeout |= type != null && (type.contains("Timeout") || type.equals("java.io.InterruptedIOException"));
        interrupted |= "java.lang.InterruptedException".equals(type);
        transport |= type != null && (type.startsWith("java.net.")
            || type.startsWith("javax.net.") || type.equals("java.io.IOException")
            || type.endsWith("ResourceAccessException"));
      }
      Status status = Objects.equals(code, 429) ? Status.QUOTA_EXCEEDED
          : interrupted ? Status.INTERRUPTED_UNKNOWN
          : timeout || Objects.equals(code, 408) || Objects.equals(code, 504) ? Status.TIMEOUT
          : code != null || transport ? Status.API_ERROR : Status.RESPONSE_ERROR;
      return result(job, status, null, null, code, elapsed);
    }
    double elapsed = elapsed(start);
    var answer = response.suggestion();
    Status status = Status.VALID;
    try {
      validator.validatePath(answer);
    } catch (ApiException invalid) {
      status = Status.INVALID_PATH;
      if (answer != null && (answer.reason() == null || answer.reason().isBlank())) {
        // Check the path independently so a missing reason does not hide a broken path.
        try {
          validator.validatePath(new AiSuggestion(answer.rootCategoryId(), answer.sectionCategoryId(),
              answer.leafCategoryId(), "Validation", null));
          status = Status.INVALID_REASON;
        } catch (ApiException invalidPath) {
          status = Status.INVALID_PATH;
        }
      }
    }
    String version = response.modelVersion();
    if (version != null && !version.matches("[A-Za-z0-9._/-]{1,120}")) version = null;
    return result(job, status, answer, version, null, elapsed, response);
  }

  static double elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000.0;
  }

  static Result result(Job job, Status status, AiSuggestion answer, String version, Integer code, Double elapsed) {
    return result(job, status, answer, version, code, elapsed, null);
  }

  private static Result result(Job job, Status status, AiSuggestion answer, String version, Integer code,
      Double elapsed, SuggestionResponse response) {
    var row = job.row();
    String root = answer == null ? null : answer.rootCategoryId();
    String section = answer == null ? null : answer.sectionCategoryId();
    String leaf = answer == null ? null : answer.leafCategoryId();
    return new Result(job.phase(), job.attempt(), row.id(), status, row.root(), row.section(),
        row.leaf(), root, section, leaf, version, code, elapsed,
        status == Status.VALID && row.root().equals(root),
        status == Status.VALID && row.section().equals(section),
        status == Status.VALID && row.leaf().equals(leaf), row.categorySource(), row.status(),
        answer == null ? null : answer.reason(), response == null ? null : response.promptTokens(),
        response == null ? null : response.completionTokens(), response == null ? null : response.totalTokens());
  }

  static void write(Path dir, List<Result> results, ObjectMapper json) throws IOException {
    var accuracy = results.stream().filter(r -> r.phase() == Phase.ACCURACY).toList();
    writeCsv(dir.resolve("accuracy-results.csv"), accuracy, false);
    json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("accuracy-results.json").toFile(), accuracy);
    writeCsv(dir.resolve("stability-results.csv"), results.stream()
        .filter(r -> r.phase() == Phase.STABILITY).toList(), false);
    var baseline = new HashMap<String, Result>();
    for (var r : results) if (r.phase() == Phase.STABILITY && r.attempt() == 1) baseline.put(r.productId(), r);
    try (var csv = EvaluationDataset.csv(dir.resolve("metamorphic-results.csv"),
        "productId", "expectedLeaf", "baselineStatus", "baselineLeaf", "reorderedStatus", "reorderedLeaf",
        "sameLeaf", "elapsedMs")) {
      for (var r : results) {
        if (r.phase() != Phase.REORDERED) continue;
        var first = baseline.get(r.productId());
        boolean comparable = first != null && first.valid() && r.valid();
        csv.printRecord(r.productId(), r.expectedLeaf(), first == null ? null : first.status(),
            first == null ? null : first.leaf(), r.status(), r.leaf(),
            comparable ? first.leaf().equals(r.leaf()) : null, r.elapsedMs());
      }
    }
    try (var csv = EvaluationDataset.csv(dir.resolve("token-usage.csv"),
        "phase", "attempt", "productId", "promptTokens", "completionTokens", "totalTokens")) {
      for (var r : results) {
        if (r.promptTokens() != null || r.completionTokens() != null || r.totalTokens() != null) {
          csv.printRecord(r.phase(), r.attempt(), r.productId(), r.promptTokens(), r.completionTokens(), r.totalTokens());
        }
      }
    }
    var attempts = results.stream().filter(Result::attempted).toList();
    writeCsv(dir.resolve("latency-results.csv"), attempts, true);
    writeCsv(dir.resolve("latency-errors.csv"), attempts.stream().filter(r -> !r.valid()).toList(), true);
  }

  private static void writeCsv(Path file, List<Result> rows, boolean latencyOnly) throws IOException {
    try (var csv = EvaluationDataset.csv(file, latencyOnly
        ? new String[]{"phase", "attempt", "productId", "status", "elapsedMs"} : HEADER)) {
      for (var r : rows) {
        if (latencyOnly) {
          csv.printRecord(r.phase(), r.attempt(), r.productId(), r.status(), r.elapsedMs());
        } else {
          csv.printRecord(r.phase(), r.attempt(), r.productId(), r.status(), r.expectedRoot(),
              r.expectedSection(), r.expectedLeaf(), r.root(), r.section(), r.leaf(), r.modelVersion(),
              r.httpStatus(), r.elapsedMs(), r.rootCorrect(), r.sectionCorrect(), r.leafCorrect(),
              r.categorySource(), r.productStatus(), r.reason(), r.promptTokens(), r.completionTokens(), r.totalTokens());
        }
      }
    }
  }

  static long count(List<Result> results, Predicate<Result> predicate) {
    return results.stream().filter(predicate).count();
  }
}
