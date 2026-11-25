package com.nearapp.aicatalogbackend.evaluation;

import com.nearapp.aicatalogbackend.exception.ApiException;
import com.nearapp.aicatalogbackend.model.*;
import com.nearapp.aicatalogbackend.repository.CategoryRepository;
import com.nearapp.aicatalogbackend.service.CategoryService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import tools.jackson.databind.ObjectMapper;

final class EvaluationDataset {
  private static final String[] HEADER = {"productId", "name", "description", "characteristics",
      "rootCategoryId", "sectionCategoryId", "leafCategoryId", "approved"};

  record Row(String id, String name, String description, Map<String, String> characteristics,
      String root, String section, String leaf, boolean approved, String categorySource, String status) {
    Row(String id, String name, String description, Map<String, String> characteristics,
        String root, String section, String leaf, boolean approved) {
      this(id, name, description, characteristics, root, section, leaf, approved, "UNSET", "UNSET");
    }
    Product product(boolean reverse) {
      var ordered = new LinkedHashMap<String, String>();
      (reverse ? characteristics.keySet().stream().sorted(Comparator.reverseOrder())
          : characteristics.keySet().stream().sorted()).forEach(key -> ordered.put(key, characteristics.get(key)));
      return new Product(id, name, description, ordered, List.of(), null,
          ProductStatus.DRAFT, null, null, null, null);
    }

    AiSuggestion reference() {
      return new AiSuggestion(root, section, leaf, "Reference", null);
    }
  }

  record Tree(List<Category> categories, Map<String, Category> byId) implements CategoryRepository {
    Tree(List<Category> categories) {
      this(categories.stream().sorted(Comparator.comparing(Category::id)).toList(),
          categories.stream().collect(Collectors.toUnmodifiableMap(Category::id, Function.identity())));
    }

    public List<Category> findAll() { return categories; }
    public Optional<Category> findById(String id) {
      return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }
    public Category save(Category category) { throw new UnsupportedOperationException(); }
    public void delete(String id) { throw new UnsupportedOperationException(); }

    CategoryService validator() { return new CategoryService(this, null, null); }
  }

  record Inspection(int candidates, int approvedCount, List<Row> rows, List<String> problems) {
    List<Row> requireApproved() {
      requireValid();
      if (approvedCount == 0) {
        throw new IllegalArgumentException(
            "No approved products found. Review dataset.csv and set approved=true for verified records.");
      }
      return rows.stream().filter(Row::approved).toList();
    }

    void requireValid() {
      if (!problems.isEmpty()) throw new IllegalArgumentException(String.join(" | ", problems));
    }
  }

  record Excluded(String productId, String categoryId, String reason) {}

  record Catalog(List<Row> rows, List<Excluded> excluded, int categoryCount, long leaves) {}

  static Catalog fromCatalog(Tree tree, List<Product> products) {
    var rows = new ArrayList<Row>();
    var excluded = new ArrayList<Excluded>();
    var validator = tree.validator();
    for (var product : products.stream().sorted(Comparator.comparing(Product::id)).toList()) {
      String categoryId = product.categoryId();
      var leaf = tree.findById(categoryId).orElse(null);
      var section = leaf == null ? null : tree.findById(leaf.parentId()).orElse(null);
      String reason = categoryId == null || categoryId.isBlank() ? "MISSING_CATEGORY"
          : leaf == null ? "UNKNOWN_CATEGORY" : leaf.level() != 3 ? "NOT_A_LEAF"
          : section == null ? "INVALID_PATH"
          : product.name() == null || product.name().isBlank()
              || !validCharacteristics(product.characteristics()) ? "INVALID_PRODUCT_FIELDS" : null;
      if (reason == null) {
        var row = new Row(product.id(), product.name(), Objects.toString(product.description(), ""),
            new TreeMap<>(product.characteristics()), section.parentId(), section.id(), leaf.id(), true,
            Objects.toString(product.categorySource(), "UNSET"), Objects.toString(product.status(), "UNSET"));
        try {
          validator.validatePath(row.reference());
          rows.add(row);
        } catch (ApiException invalid) {
          reason = "INVALID_PATH";
        }
      }
      if (reason != null) excluded.add(new Excluded(product.id(), categoryId, reason));
    }
    return new Catalog(rows, excluded, tree.categories().size(),
        tree.categories().stream().filter(category -> category.level() == 3).count());
  }

  static void writeCatalog(Path dir, Catalog catalog, Tree tree, ObjectMapper json) throws IOException {
    try (var csv = csv(dir.resolve("dataset.csv"), "productId", "name", "description", "characteristics",
        "rootCategoryId", "sectionCategoryId", "leafCategoryId", "categorySource", "status")) {
      for (var row : catalog.rows()) {
        csv.printRecord(row.id(), row.name(), row.description(), json.writeValueAsString(row.characteristics()),
            row.root(), row.section(), row.leaf(), row.categorySource(), row.status());
      }
    }
    try (var csv = csv(dir.resolve("dataset-excluded.csv"), "productId", "categoryId", "reason")) {
      for (var row : catalog.excluded()) csv.printRecord(row.productId(), row.categoryId(), row.reason());
    }
    try (var csv = csv(dir.resolve("dataset-coverage.csv"), "leafCategoryId", "name", "productCount")) {
      var counts = catalog.rows().stream().collect(Collectors.groupingBy(Row::leaf, Collectors.counting()));
      for (var category : tree.categories()) {
        if (category.level() == 3) csv.printRecord(category.id(), category.name(), counts.getOrDefault(category.id(), 0L));
      }
    }
  }

  static void prepare(Path directory, Path review, Tree tree, List<Product> products, ObjectMapper json)
      throws IOException {
    var selected = new LinkedHashMap<String, Row>();
    var usedIds = new HashSet<String>();
    if (Files.exists(review)) {
      var previous = inspect(review, tree, json);
      if (previous.candidates() > 0) previous.requireValid();
      else if (!previous.problems().equals(List.of("Dataset CSV has no product records."))) previous.requireValid();
      for (var row : previous.rows()) {
        selected.put(row.leaf(), row);
        usedIds.add(row.id());
      }
    }

    var available = new HashMap<String, Integer>();
    var paths = new HashMap<String, AiSuggestion>();
    var validator = tree.validator();
    for (var category : tree.categories()) {
      if (category.level() != 3) continue;
      var section = tree.findById(category.parentId()).orElse(null);
      if (section == null) continue;
      var path = new AiSuggestion(section.parentId(), section.id(), category.id(), "Reference", null);
      try {
        validator.validatePath(path);
        paths.put(category.id(), path);
      } catch (ApiException invalidPath) {
        // Keep malformed leaves visible in the coverage report.
      }
    }

    int noCategory = 0, unknownCategory = 0, nonLeaf = 0, brokenPath = 0, invalidProduct = 0;
    var sources = new TreeMap<String, Integer>();
    var statuses = new TreeMap<String, Integer>();
    var ordered = products.stream()
        .sorted(Comparator.comparing((Product p) -> p.categorySource() != CategorySource.MANUAL)
            .thenComparing(Product::id)).toList();
    for (var product : ordered) {
      sources.merge(Objects.toString(product.categorySource(), "UNSET"), 1, Integer::sum);
      statuses.merge(Objects.toString(product.status(), "UNSET"), 1, Integer::sum);
      if (product.categoryId() == null || product.categoryId().isBlank()) {
        noCategory++;
        continue;
      }
      var category = tree.findById(product.categoryId()).orElse(null);
      if (category == null) {
        unknownCategory++;
        continue;
      }
      if (category.level() != 3) {
        nonLeaf++;
        continue;
      }
      var path = paths.get(category.id());
      if (path == null) {
        brokenPath++;
        continue;
      }
      if (product.name() == null || product.name().isBlank()
          || !validCharacteristics(product.characteristics())) {
        invalidProduct++;
        continue;
      }
      available.merge(category.id(), 1, Integer::sum);
      if (selected.containsKey(category.id()) || !usedIds.add(product.id())) continue;
      selected.put(category.id(), new Row(product.id(), product.name(), Objects.toString(product.description(), ""),
          new TreeMap<>(product.characteristics()), path.rootCategoryId(), path.sectionCategoryId(),
          path.leafCategoryId(), false));
    }

    write(directory.resolve("dataset.csv"), selected.values(), json);
    try (var coverage = csv(directory.resolve("dataset-coverage.csv"),
        "leafCategoryId", "name", "availableProductCount", "selectedProductId", "status", "reason")) {
      for (var category : tree.categories()) {
        if (category.level() != 3) continue;
        var candidate = selected.get(category.id());
        int count = available.getOrDefault(category.id(), 0);
        String reason = candidate != null ? "" : !paths.containsKey(category.id()) ? "INVALID_CATEGORY_PATH"
            : count == 0 ? "NO_USABLE_PRODUCT_ASSIGNED_TO_LEAF" : "PRODUCT_ALREADY_IN_REVIEW";
        coverage.printRecord(category.id(), category.name(), count, candidate == null ? "" : candidate.id(),
            candidate == null ? "NO_CANDIDATE" : candidate.approved() ? "APPROVED" : "REVIEW_REQUIRED", reason);
      }
    }

    var stats = new LinkedHashMap<String, Object>();
    stats.put("productsRead", products.size());
    stats.put("productStatuses", statuses);
    stats.put("categorySources", sources);
    stats.put("leafCategories", tree.categories().stream().filter(c -> c.level() == 3).count());
    stats.put("productsWithoutCategory", noCategory);
    stats.put("productsWithUnknownCategory", unknownCategory);
    stats.put("productsWithNonLeafCategory", nonLeaf);
    stats.put("productsWithBrokenPath", brokenPath);
    stats.put("productsWithInvalidFields", invalidProduct);
    stats.put("eligibleProducts", available.values().stream().mapToInt(Integer::intValue).sum());
    stats.put("candidateRows", selected.size());
    stats.put("approvedRows", selected.values().stream().filter(Row::approved).count());
    json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("prepare-summary.json").toFile(), stats);

    // Publish the first working copy. Subsequent runs merge into their own snapshot
    // so an open editor or concurrent prepare cannot overwrite the working file.
    if (!Files.exists(review)) {
      Files.copy(directory.resolve("dataset.csv"), review);
    }
    System.out.println("Products read: " + products.size() + "; candidate rows: " + selected.size()
        + "; approved: " + stats.get("approvedRows"));
  }

  static List<Row> approved(Path file, Tree tree, ObjectMapper json) throws IOException {
    return inspect(file, tree, json).requireApproved();
  }

  static Inspection inspect(Path file, Tree tree, ObjectMapper json) throws IOException {
    if (!Files.isRegularFile(file)) {
      return new Inspection(0, 0, List.of(), List.of("Dataset CSV does not exist. Run prepare first."));
    }
    var rows = new ArrayList<Row>();
    var problems = new ArrayList<String>();
    var ids = new HashSet<String>();
    var leaves = new HashSet<String>();
    var validator = tree.validator();
    int candidates = 0, approvals = 0;
    try (var reader = Files.newBufferedReader(file)) {
      reader.mark(1);
      if (reader.read() != '\uFEFF') reader.reset();
      try (var csv = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
        if (!csv.getHeaderNames().equals(List.of(HEADER))) {
          return new Inspection(0, 0, List.of(), List.of("Invalid dataset CSV header. Use the generated UTF-8 CSV."));
        }
        for (var record : csv) {
          candidates++;
          String problem = null;
          if (!record.isConsistent()) {
            problems.add("Row " + record.getRecordNumber() + ": incorrect column count");
            continue;
          }
          String approval = record.get("approved").strip();
          boolean approved = approval.equalsIgnoreCase("true");
          if (approved) approvals++;
          try {
            if (!approved && !approval.equalsIgnoreCase("false") && !approval.isEmpty()) {
              throw new IllegalArgumentException("Use true or false in approved");
            }
            String id = record.get("productId");
            String name = record.get("name");
            String leaf = record.get("leafCategoryId");
            if (id.isBlank() || name.isBlank()) throw new IllegalArgumentException("productId and name are required");
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate productId");
            if (!leaves.add(leaf)) throw new IllegalArgumentException("Duplicate leafCategoryId");
            var fields = json.readTree(record.get("characteristics"));
            if (fields == null || !fields.isObject()) {
              throw new IllegalArgumentException("characteristics must be a JSON object of text values");
            }
            var characteristics = new LinkedHashMap<String, String>();
            for (var field : fields.properties()) {
              if (!field.getValue().isTextual()) {
                throw new IllegalArgumentException("characteristics must be a JSON object of text values");
              }
              characteristics.put(field.getKey(), field.getValue().asText());
            }
            var row = new Row(id, name, record.get("description"), characteristics,
                record.get("rootCategoryId"), record.get("sectionCategoryId"), leaf, approved);
            validator.validatePath(row.reference());
            rows.add(row);
          } catch (ApiException invalidPath) {
            problem = "Invalid three-level reference path";
          } catch (tools.jackson.core.JacksonException invalidJson) {
            problem = "Invalid characteristics JSON";
          } catch (IllegalArgumentException invalidRow) {
            problem = invalidRow.getMessage();
          }
          if (problem != null) problems.add("Row " + record.getRecordNumber() + ": " + problem);
        }
      }
    } catch (java.io.UncheckedIOException | IllegalArgumentException invalidCsv) {
      problems.add("Invalid CSV format. Save as UTF-8 CSV with commas and the generated header.");
    }
    if (candidates == 0 && problems.isEmpty()) problems.add("Dataset CSV has no product records.");
    return new Inspection(candidates, approvals, List.copyOf(rows), List.copyOf(problems));
  }

  private static boolean validCharacteristics(Map<String, String> characteristics) {
    return characteristics != null && characteristics.values().stream().allMatch(String.class::isInstance);
  }

  static void write(Path path, Collection<Row> rows, ObjectMapper json) throws IOException {
    try (var csv = csv(path, HEADER)) {
      for (var row : rows) {
        csv.printRecord(row.id(), row.name(), row.description(), json.writeValueAsString(row.characteristics()),
            row.root(), row.section(), row.leaf(), row.approved());
      }
    }
  }

  static org.apache.commons.csv.CSVPrinter csv(Path path, String... header) throws IOException {
    return CSVFormat.DEFAULT.builder().setHeader(header).build().print(path, java.nio.charset.StandardCharsets.UTF_8);
  }
}
