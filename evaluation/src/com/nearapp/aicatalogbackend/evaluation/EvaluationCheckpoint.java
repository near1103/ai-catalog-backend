package com.nearapp.aicatalogbackend.evaluation;

import com.nearapp.aicatalogbackend.evaluation.GeminiEvaluation.Job;
import com.nearapp.aicatalogbackend.evaluation.GeminiEvaluation.Result;
import com.nearapp.aicatalogbackend.evaluation.GeminiEvaluation.Status;
import com.nearapp.aicatalogbackend.service.GeminiCategorySuggestionService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

final class EvaluationCheckpoint {
  static final int FORMAT_VERSION = 1;

  record Settings(int limit, int stabilityCount, int repeats, boolean reorder, long delayMs, int maxRequests) {}

  record Manifest(int format, String startedAt, Settings settings, String snapshotHash, String treeHash,
      String model, String optionsHash, String promptHash, String planHash, int plannedRequests,
      EvaluationReport.Offline offline) {}

  private final Path directory;
  private final ObjectMapper json;

  EvaluationCheckpoint(Path directory, ObjectMapper json) throws IOException {
    this.directory = Files.createDirectories(directory.resolve("checkpoints"));
    this.json = json;
  }

  static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  static void save(Path path, Object value, ObjectMapper json) throws IOException {
    var temporary = Files.createTempFile(path.getParent(), ".evaluation-", ".tmp");
    try {
      var bytes = json.writeValueAsBytes(value);
      try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) file.write(buffer);
        file.force(true);
      }
      Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private Path entry(int index) {
    return directory.resolve("%06d.json".formatted(index));
  }

  void load(List<Job> jobs, List<Result> results) throws IOException {
    for (int index = 0; index < jobs.size(); index++) {
      var path = entry(index);
      if (!Files.exists(path)) break;
      var result = json.readValue(path.toFile(), Result.class);
      var job = jobs.get(index);
      if (result.phase() != job.phase() || result.attempt() != job.attempt()
          || !Objects.equals(result.productId(), job.row().id()) || result.status() == Status.NOT_RUN) {
        throw new IOException("Checkpoint does not match the saved plan at index " + index);
      }
      results.add(result);
    }
    try (var files = Files.list(directory)) {
      if (files.filter(path -> path.getFileName().toString().matches("[0-9]+[.]json")).count() != results.size()) {
        throw new IOException("Checkpoint sequence has missing or unexpected entries");
      }
    }
  }

  void execute(List<Job> jobs, List<Result> results, EvaluationDataset.Tree tree,
      GeminiCategorySuggestionService service, long delayMs) throws IOException, InterruptedException {
    var validator = tree.validator();
    for (int index = results.size(); index < jobs.size(); index++) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      if (index > 0) Thread.sleep(delayMs);
      var job = jobs.get(index);
      var path = entry(index);
      var reserved = GeminiEvaluation.result(job, Status.INTERRUPTED_UNKNOWN, null, null, null, null);
      save(path, reserved, json);
      results.add(reserved);
      var result = GeminiEvaluation.classify(job, tree, validator, service);
      // Clear an interrupt temporarily so the completed result can reach the checkpoint.
      boolean interrupted = Thread.interrupted();
      try {
        save(path, result, json);
        results.set(index, result);
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
      if (interrupted || shouldPause(result)) break;
    }
  }

  private static boolean shouldPause(Result result) {
    var code = result.httpStatus();
    return switch (result.status()) {
      case QUOTA_EXCEEDED, TIMEOUT, INTERRUPTED_UNKNOWN -> true;
      case API_ERROR -> code == null || code == 401 || code == 403 || code >= 500;
      default -> false;
    };
  }
}
