package com.nearapp.aicatalogbackend.evaluation;

import static com.nearapp.aicatalogbackend.evaluation.GeminiEvaluation.count;

import com.nearapp.aicatalogbackend.evaluation.GeminiEvaluation.Phase;
import com.nearapp.aicatalogbackend.evaluation.GeminiEvaluation.Result;
import com.nearapp.aicatalogbackend.evaluation.GeminiEvaluation.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import tools.jackson.databind.ObjectMapper;

final class EvaluationReport {
  record Check(String suite, String name, String outcome) {}
  record TestCounts(int passed, int failed, int skipped) {}
  record Offline(String status, TestCounts functional, TestCounts reliability, Map<String, TestCounts> reliabilityTypes) {}

  static Offline offline(Path dir, Path junit, String status) throws Exception {
    var checks = new ArrayList<Check>();
    if (junit != null && Files.isDirectory(junit)) {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      var parser = factory.newDocumentBuilder();
      try (var paths = Files.list(junit)) {
        for (var path : paths.filter(p -> p.getFileName().toString().matches("TEST-.*[.]xml")).sorted().toList()) {
          var cases = parser.parse(path.toFile()).getElementsByTagName("testcase");
          for (int i = 0; i < cases.getLength(); i++) {
            var test = (Element) cases.item(i);
            String outcome = has(test, "failure") || has(test, "error") ? "Невдала"
                : has(test, "skipped") ? "Пропущена" : "Успішна";
            checks.add(new Check(test.getAttribute("classname").replaceFirst("^.*[.]", ""),
                test.getAttribute("name"), outcome));
          }
        }
      }
    }
    var reliability = checks.stream().filter(c -> c.suite().equals("AiReliabilityTests")
        || c.suite().equals("GeminiSuggestionTests")
        || c.name().matches("(manualCategoryWorksWhenAiFails|invalidAiPathIsNotSaved).*")).toList();
    var reliabilityTypes = new TreeMap<String, TestCounts>();
    for (var group : reliability.stream().collect(java.util.stream.Collectors.groupingBy(Check::suite)).entrySet()) {
      reliabilityTypes.put(group.getKey(), summarize(group.getValue()));
    }
    return new Offline(status,
        testReport(dir.resolve("functional-tests.md"), "Функціональні перевірки та регресійний набір", checks, status),
        testReport(dir.resolve("reliability-tests.md"), "Надійність ШІ-категоризації", reliability, status), reliabilityTypes);
  }

  private static boolean has(Element test, String tag) {
    return test.getElementsByTagName(tag).getLength() > 0;
  }

  private static TestCounts testReport(Path file, String title, List<Check> checks, String status)
      throws IOException {
    var counts = summarize(checks);
    var text = new StringBuilder("# ").append(title).append("\n\nСтан запуску — ").append(status).append("\n\n");
    text.append("| Успішні | Невдалі | Пропущені |\n|---:|---:|---:|\n");
    text.append("| ").append(counts.passed()).append(" | ").append(counts.failed())
        .append(" | ").append(counts.skipped()).append(" |\n");
    if (checks.isEmpty()) text.append("\nДоступних результатів JUnit немає.\n");
    else {
      text.append("\n| Клас | Перевірка | Результат |\n|---|---|---|\n");
      for (var c : checks) text.append("| ").append(cell(c.suite())).append(" | ")
          .append(cell(c.name())).append(" | ").append(c.outcome()).append(" |\n");
    }
    Files.writeString(file, text);
    return counts;
  }

  private static TestCounts summarize(List<Check> checks) {
    return new TestCounts((int) checks.stream().filter(c -> c.outcome().equals("Успішна")).count(),
        (int) checks.stream().filter(c -> c.outcome().equals("Невдала")).count(),
        (int) checks.stream().filter(c -> c.outcome().equals("Пропущена")).count());
  }

  static void write(Path dir, Offline offline, Map<String, Object> parameters, String liveState,
      List<Result> results, ObjectMapper json) throws IOException {
    write(dir, offline, parameters, liveState, results, json, List.of(), new EvaluationDataset.Tree(List.of()));
  }

  static void write(Path dir, Offline offline, Map<String, Object> parameters, String liveState,
      List<Result> results, ObjectMapper json, List<EvaluationDataset.Row> rows, EvaluationDataset.Tree tree)
      throws IOException {
    var accuracy = results.stream().filter(r -> r.phase() == Phase.ACCURACY).toList();
    var validAccuracy = accuracy.stream().filter(Result::valid).toList();
    var attempts = results.stream().filter(Result::attempted).toList();
    var summary = new LinkedHashMap<String, Object>();
    summary.put("parameters", parameters);
    summary.put("offline", offline);
    summary.put("liveStatus", liveState);
    summary.put("responseModelVersions", results.stream().map(Result::modelVersion)
        .filter(v -> v != null && !v.isBlank()).distinct().sorted().toList());

    var metrics = new LinkedHashMap<String, Object>();
    summary.put("accuracy", metrics);
    metrics.put("planned", accuracy.size());
    metrics.put("tested", count(accuracy, Result::attempted));
    metrics.put("structuredResponses", count(accuracy, r -> r.valid()
        || r.status() == Status.INVALID_PATH || r.status() == Status.INVALID_REASON));
    metrics.put("apiErrors", count(accuracy, r -> r.status() == Status.API_ERROR || r.status() == Status.QUOTA_EXCEEDED
        || r.status() == Status.TIMEOUT));
    metrics.put("correctPaths", count(validAccuracy, Result::correctPath));
    metrics.put("wrongValidPaths", count(validAccuracy, r -> !r.correctPath()));
    metrics.put("interruptedUnknown", count(accuracy, r -> r.status() == Status.INTERRUPTED_UNKNOWN));
    metrics.put("responseProcessingErrors", count(accuracy, r -> r.status() == Status.RESPONSE_ERROR));
    metrics.put("invalidPaths", count(accuracy, r -> r.status() == Status.INVALID_PATH));
    metrics.put("invalidReasons", count(accuracy, r -> r.status() == Status.INVALID_REASON));
    metrics.put("validResponses", validAccuracy.size());
    metrics.put("correctRoot", count(accuracy, Result::rootCorrect));
    metrics.put("correctSection", count(accuracy, Result::sectionCorrect));
    metrics.put("correctLeaf", count(accuracy, Result::leafCorrect));
    metrics.put("wrongLeafAmongValid", count(validAccuracy, r -> !r.leafCorrect()));
    metrics.put("validRatePerAttempt", ratio(validAccuracy.size(), count(accuracy, Result::attempted)));
    metrics.put("validRatePerPlan", ratio(validAccuracy.size(), accuracy.size()));
    metrics.put("rootAccuracyValid", score(validAccuracy, Result::rootCorrect));
    metrics.put("sectionAccuracyValid", score(validAccuracy, Result::sectionCorrect));
    metrics.put("top1AccuracyValid", score(validAccuracy, Result::leafCorrect));
    metrics.put("rootAccuracyPlanned", score(accuracy, Result::rootCorrect));
    metrics.put("sectionAccuracyPlanned", score(accuracy, Result::sectionCorrect));
    metrics.put("top1AccuracyPlanned", score(accuracy, Result::leafCorrect));

    var stability = stability(results);
    summary.put("stability", stability);
    var metamorphic = metamorphic(results);
    summary.put("metamorphic", metamorphic);
    var latency = latency(attempts);
    summary.put("latency", latency);
    var latencyByPhase = new LinkedHashMap<String, Map<String, Object>>();
    for (var phase : Phase.values()) {
      latencyByPhase.put(phase.name(), latency(attempts.stream().filter(r -> r.phase() == phase).toList()));
    }
    summary.put("latencyByPhase", latencyByPhase);
    var roots = grouped(accuracy, Result::expectedRoot, tree);
    var sources = grouped(accuracy, Result::categorySource, new EvaluationDataset.Tree(List.of()));
    summary.put("accuracyByRoot", roots);
    summary.put("accuracyByCategorySource", sources);
    var statuses = new LinkedHashMap<Status, Long>();
    for (var status : Status.values()) statuses.put(status, count(results, r -> r.status() == status));
    summary.put("allPhasesStatusCounts", statuses);

    var text = new StringBuilder("# Оцінювання системи категоризації товарів\n\n");
    text.append("Функціональні перевірки — ").append(offline.status()).append(". Gemini — ")
        .append(liveState).append(".\n\n");
    table(text, "Параметри запуску та середовище", parameters, Map.of());
    text.append("\nВерсії моделі з відповідей Gemini — ")
        .append(cell(Objects.toString(summary.get("responseModelVersions")))).append(". Порожній список означає, що метадані версії недоступні.\n");
    text.append("\n## Методика\n\nJUnit запускає регресійні та функціональні тести з ізольованими залежностями. ")
        .append("У режимі live Gemini отримує тільки назву, опис, характеристики й повне дерево категорій. ")
        .append("Еталонні шляхи взяті зі збережених призначень Firestore після структурної перевірки. Незалежна експертна розмітка відсутня. ")
        .append("Для categorySource=AI збіг вимірює узгодженість із каталогом. Запропоновані шляхи перевіряє CategoryService. ")
        .append("Точність серед валідних відповідей враховує тільки відповіді з коректним шляхом і поясненням. ")
        .append("Частка від плану враховує всі заплановані товари, включно з помилками та невиконаними запитами. ")
        .append("Рядки з API-помилками підраховуються окремо від хибних категорій. ")
        .append("Для стабільності виконуються окремі повтори з однаковим входом. Опційна перестановка змінює порядок характеристик, а порівняння виконується з першим повтором.\n");
    text.append("\n## Таблиця 1. Функціональне тестування\n\n| Набір | Усього | Успішні | Невдалі | Пропущені |\n|---|---:|---:|---:|---:|\n");
    suite(text, "Функціональні та регресійні", offline.functional());
    table(text, "Таблиця 2. Точність категоризації", metrics, ACCURACY_LABELS);
    groupedTable(text, "Таблиця 3. Точність за кореневими категоріями", roots);
    groupedTable(text, "Точність за джерелом еталонної категорії", sources);
    table(text, "Таблиця 4. Стабільність категоризації", stability, STABILITY_LABELS);
    text.append("\nЧас вимірюється для підсистеми категоризації Gemini від підготовки запиту в сервісі до обробки відповіді. ")
        .append("Завантаження вебсторінки, читання Firestore, валідація еталонів і штучні паузи лежать за межами вимірювання. ")
        .append("Статистика часу й критерій 15 секунд використовують валідні відповіді. Тривалість кожної помилки доступна в latency-results.csv за статусом. P95 обчислюється методом найближчого рангу.\n");
    table(text, "Таблиця 5. Час категоризації", latency, LATENCY_LABELS);
    table(text, "Час основних запитів", latencyByPhase.get("ACCURACY"), LATENCY_LABELS);
    table(text, "Час повторних запитів", latencyByPhase.get("STABILITY"), LATENCY_LABELS);
    if (results.stream().anyMatch(r -> r.phase() == Phase.REORDERED)) {
      table(text, "Метаморфічна перевірка", metamorphic, METAMORPHIC_LABELS);
      table(text, "Час метаморфічних запитів", latencyByPhase.get("REORDERED"), LATENCY_LABELS);
    }
    text.append("\n## Таблиця 6. Перевірки надійності\n\n| Тип | Усього | Успішні | Невдалі | Пропущені |\n|---|---:|---:|---:|---:|\n");
    offline.reliabilityTypes().forEach((type, counts) -> suite(text, type, counts));
    suite(text, "Усі перевірки надійності", offline.reliability());
    examples(text, validAccuracy, rows, tree);
    Files.writeString(dir.resolve("report.md"), text);
    json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("summary.json").toFile(), summary);
  }

  private static Map<String, Object> stability(List<Result> results) {
    var byProduct = new LinkedHashMap<String, List<Result>>();
    for (var result : results) {
      if (result.phase() == Phase.STABILITY) byProduct.computeIfAbsent(result.productId(), id -> new ArrayList<>()).add(result);
    }
    long same = 0, different = 0, incomplete = 0, allPathsValid = 0, correct = 0;
    for (var group : byProduct.values()) {
      correct += count(group, Result::leafCorrect);
      if (group.stream().allMatch(r -> r.valid() || r.status() == Status.INVALID_REASON)) allPathsValid++;
      if (!group.stream().allMatch(Result::valid)) incomplete++;
      else if (group.stream().map(Result::leaf).distinct().count() == 1) same++;
      else different++;
    }
    var trials = results.stream().filter(r -> r.phase() == Phase.STABILITY).toList();
    long attempted = count(trials, Result::attempted);
    var metrics = new LinkedHashMap<String, Object>();
    metrics.put("products", byProduct.size());
    metrics.put("sameLeaf", same);
    metrics.put("sameLeafShare", ratio(same, same + different));
    metrics.put("differentLeaf", different);
    metrics.put("incompleteGroups", incomplete);
    metrics.put("allPathsValid", allPathsValid);
    metrics.put("correctTrials", correct);
    metrics.put("attemptedTrials", attempted);
    metrics.put("correctTrialShare", ratio(correct, attempted));
    metrics.put("validTrialShare", ratio(count(trials, Result::valid), attempted));
    metrics.put("structuredTrialShare", ratio(count(trials, r -> r.valid() || r.status() == Status.INVALID_REASON), attempted));
    return metrics;
  }

  private static Map<String, Object> metamorphic(List<Result> results) {
    var baseline = new HashMap<String, Result>();
    for (var r : results) if (r.phase() == Phase.STABILITY && r.attempt() == 1) baseline.put(r.productId(), r);
    var reordered = results.stream().filter(r -> r.phase() == Phase.REORDERED).toList();
    long comparable = 0, unchanged = 0;
    for (var result : reordered) {
      var first = baseline.get(result.productId());
      if (first != null && result.valid() && first.valid()) {
        comparable++;
        if (result.leaf().equals(first.leaf())) unchanged++;
      }
    }
    var metrics = new LinkedHashMap<String, Object>();
    metrics.put("reorderPlanned", reordered.size());
    metrics.put("reorderComparable", comparable);
    metrics.put("reorderUnchanged", unchanged);
    metrics.put("reorderChanged", comparable - unchanged);
    return metrics;
  }

  private static Map<String, Object> latency(List<Result> attempts) {
    var sorted = attempts.stream().filter(Result::valid).map(Result::elapsedMs).filter(Objects::nonNull).sorted().toList();
    int n = sorted.size();
    var metrics = new LinkedHashMap<String, Object>();
    metrics.put("attempts", attempts.size());
    metrics.put("nonValidAttempts", attempts.size() - n);
    metrics.put("minSeconds", n == 0 ? null : sorted.getFirst() / 1000);
    metrics.put("maxSeconds", n == 0 ? null : sorted.getLast() / 1000);
    metrics.put("meanSeconds", n == 0 ? null : sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow() / 1000);
    metrics.put("medianSeconds", n == 0 ? null : (sorted.get((n - 1) / 2) + sorted.get(n / 2)) / 2000);
    metrics.put("p95Seconds", n == 0 ? null : sorted.get((int) Math.ceil(n * 0.95) - 1) / 1000);
    long valid = count(attempts, Result::valid);
    long fast = count(attempts, r -> r.valid() && r.elapsedMs() <= 15000);
    var share = ratio(fast, valid);
    metrics.put("validAttempts", valid);
    metrics.put("validWithin15Seconds", fast);
    metrics.put("within15SecondsRate", share);
    metrics.put("requirementMet", share == null ? null : share >= 0.95);
    return metrics;
  }

  private static Double score(List<Result> rows, Predicate<Result> predicate) {
    return ratio(count(rows, predicate), rows.size());
  }

  private static Double ratio(long numerator, long denominator) {
    return denominator == 0 ? null : (double) numerator / denominator;
  }

  private static void suite(StringBuilder text, String title, TestCounts counts) {
    text.append("| ").append(title).append(" | ").append(counts.passed() + counts.failed() + counts.skipped())
        .append(" | ").append(counts.passed()).append(" | ")
        .append(counts.failed()).append(" | ").append(counts.skipped()).append(" |\n");
  }

  private static void table(StringBuilder text, String title, Map<String, Object> values, Map<String, String> labels) {
    text.append("\n## ").append(title).append("\n\n| Показник | Значення |\n|---|---:|\n");
    values.forEach((key, value) -> {
      String display = switch (value) {
        case null -> "н/д";
        case Double number when key.endsWith("Seconds") -> String.format(Locale.ROOT, "%.3f", number);
        case Double number -> String.format(Locale.ROOT, "%.2f%%", number * 100);
        case Boolean yes -> yes ? "Так" : "Ні";
        default -> value.toString();
      };
      text.append("| ").append(labels.getOrDefault(key, key)).append(" | ").append(cell(display)).append(" |\n");
    });
  }

  private record Group(String id, String name, int planned, long valid, long correct, Double accuracyValid,
      Double accuracyPlanned) {}

  private static List<Group> grouped(List<Result> results, java.util.function.Function<Result, String> key,
      EvaluationDataset.Tree tree) {
    var groups = new TreeMap<String, List<Result>>();
    for (var result : results) groups.computeIfAbsent(key.apply(result), id -> new ArrayList<>()).add(result);
    return groups.entrySet().stream().map(entry -> {
      String id = entry.getKey();
      var group = entry.getValue();
      long valid = count(group, Result::valid);
      long correct = count(group, Result::correctPath);
      return new Group(id, label(tree, id), group.size(), valid, correct, ratio(correct, valid), ratio(correct, group.size()));
    }).toList();
  }

  private static String label(EvaluationDataset.Tree tree, String id) {
    return tree.findById(id).map(category -> category.name() + " (" + id + ")").orElse(id);
  }

  private static String percent(Double value) {
    return value == null ? "н/д" : String.format(Locale.ROOT, "%.2f%%", value * 100);
  }

  private static void groupedTable(StringBuilder text, String title, List<Group> groups) {
    text.append("\n## ").append(title)
        .append("\n\n| Група | Товарів | Валідні | Правильні шляхи | Точність серед валідних | Частка від плану |\n")
        .append("|---|---:|---:|---:|---:|---:|\n");
    for (var g : groups) text.append("| ").append(cell(g.name())).append(" | ").append(g.planned())
        .append(" | ").append(g.valid()).append(" | ").append(g.correct()).append(" | ")
        .append(percent(g.accuracyValid())).append(" | ").append(percent(g.accuracyPlanned())).append(" |\n");
  }

  private static void examples(StringBuilder text, List<Result> valid, List<EvaluationDataset.Row> rows,
      EvaluationDataset.Tree tree) {
    var wrong = valid.stream().filter(r -> !r.correctPath()).limit(10).toList();
    if (wrong.isEmpty()) return;
    var names = new HashMap<String, String>();
    rows.forEach(row -> names.put(row.id(), row.name()));
    text.append("\n## Приклади неправильної категоризації\n\n| Товар | Еталонна кінцева категорія | Категорія Gemini | Перший рівень розбіжності |\n|---|---|---|---|\n");
    for (var r : wrong) text.append("| ").append(cell(names.getOrDefault(r.productId(), r.productId())))
        .append(" | ").append(cell(label(tree, r.expectedLeaf()))).append(" | ").append(cell(label(tree, r.leaf())))
        .append(" | ").append(!r.rootCorrect() ? "Кореневий" : !r.sectionCorrect() ? "Другий" : "Кінцевий")
        .append(" |\n");
  }

  private static String cell(String text) {
    return text.replace("|", "\\|").replace("\r", " ").replace("\n", " ").replace("<", "&lt;");
  }

  private static final Map<String, String> ACCURACY_LABELS = Map.ofEntries(
      Map.entry("correctPaths", "Повністю правильні шляхи"), Map.entry("wrongValidPaths", "Хибні валідні шляхи"),
      Map.entry("interruptedUnknown", "Перервані виклики з невідомим результатом"),
      Map.entry("planned", "Заплановано товарів"), Map.entry("tested", "Протестовано товарів"),
      Map.entry("structuredResponses", "Отримано структурованих відповідей Gemini"),
      Map.entry("apiErrors", "Помилки API, зокрема квота"), Map.entry("responseProcessingErrors", "Помилки обробки відповіді"),
      Map.entry("invalidPaths", "Некоректна ієрархія"), Map.entry("invalidReasons", "Порожнє або відсутнє пояснення"),
      Map.entry("validResponses", "Успішно оброблено"), Map.entry("wrongLeafAmongValid", "Хибні кінцеві категорії серед валідних відповідей"),
      Map.entry("correctRoot", "Правильно визначено кореневу категорію"), Map.entry("correctSection", "Правильно визначено другий рівень"),
      Map.entry("correctLeaf", "Правильно визначено кінцеву категорію"),
      Map.entry("validRatePerAttempt", "Успішно оброблено від кількості викликів"),
      Map.entry("validRatePerPlan", "Успішно оброблено від плану"),
      Map.entry("rootAccuracyValid", "Кореневий рівень серед валідних"), Map.entry("sectionAccuracyValid", "Другий рівень серед валідних"),
      Map.entry("top1AccuracyValid", "Top-1 Accuracy серед валідних"), Map.entry("rootAccuracyPlanned", "Кореневий рівень від плану"),
      Map.entry("sectionAccuracyPlanned", "Другий рівень від плану"), Map.entry("top1AccuracyPlanned", "Top-1 Accuracy від плану"));

  private static final Map<String, String> STABILITY_LABELS = Map.ofEntries(
      Map.entry("products", "Товарів у підмножині"), Map.entry("sameLeaf", "Однакова кінцева категорія у всіх повторах"),
      Map.entry("differentLeaf", "Кінцева категорія відрізнялася"), Map.entry("incompleteGroups", "Групи без повного набору валідних відповідей"),
      Map.entry("allPathsValid", "Товарів із коректною ієрархією в усіх повторах"),
      Map.entry("attemptedTrials", "Виконано повторних викликів"), Map.entry("correctTrials", "Збіги з еталоном у повторах"),
      Map.entry("sameLeafShare", "Частка однакових серед повністю виміряних товарів"),
      Map.entry("correctTrialShare", "Частка правильних серед виконаних повторів"),
      Map.entry("validTrialShare", "Частка валідних серед виконаних повторів"),
      Map.entry("structuredTrialShare", "Частка коректних шляхів серед виконаних повторів"));

  private static final Map<String, String> METAMORPHIC_LABELS = Map.ofEntries(
      Map.entry("reorderPlanned", "Заплановано перестановок"), Map.entry("reorderComparable", "Придатні до порівняння перестановки"),
      Map.entry("reorderUnchanged", "Категорія збіглася після перестановки"), Map.entry("reorderChanged", "Категорія змінилася після перестановки"));

  private static final Map<String, String> LATENCY_LABELS = Map.ofEntries(
      Map.entry("attempts", "Зафіксовані або зарезервовані виклики"),
      Map.entry("nonValidAttempts", "Виклики без валідної відповіді"), Map.entry("minSeconds", "Мінімум, с"), Map.entry("maxSeconds", "Максимум, с"),
      Map.entry("meanSeconds", "Середнє, с"), Map.entry("medianSeconds", "Медіана, с"), Map.entry("p95Seconds", "P95, с"),
      Map.entry("validAttempts", "Успішні виклики"), Map.entry("validWithin15Seconds", "Успішні виклики до 15 с включно"),
      Map.entry("within15SecondsRate", "Частка успішних викликів до 15 с включно"),
      Map.entry("requirementMet", "Виконано вимогу ≥95% успішних викликів до 15 с"));
}
