package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compares vision models on the extraction pass, against a real Ollama.
 *
 * <p><b>Not a test:</b> it asserts nothing, it measures. Off unless explicitly asked for
 * ({@code ARBITER_BENCH=1}) because it takes tens of minutes per model on CPU and needs Ollama up
 * with the models already pulled. It exists to close the model-investigation card (Sprint 8) with
 * a table instead of an opinion.
 *
 * <p>It runs the <b>real code</b> — {@link OllamaDocumentAnalyzer}, its prompt, its schema and its
 * 150 DPI rasterizing — and only swaps {@code arbiter.ollama.model} between runs. Any difference
 * in the table comes from the model, not from the harness.
 *
 * <h2>How the OCR score works</h2>
 * Ground truth isn't hand-written: it comes from the PDF itself. The fixtures are generated with
 * selectable text (house rule in {@code docs/postman/test-docs/README.md}), so
 * {@link PDFTextStripper} returns exactly what the paper says. From that we take the <i>hard
 * tokens</i> — IMEI, report numbers, amounts, dates, CUIT, serials — which are what the rules
 * compare, and measure how many of those made it into the model's transcription.
 *
 * <p>Hard tokens rather than words because a transcription can paraphrase the narrative and still
 * be perfectly useful, whereas one digit off in the IMEI breaks
 * {@code DocumentInconsistencyEvaluator} silently. What gets measured is what matters.
 *
 * <h2>Running it</h2>
 * <pre>
 * $env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.11"
 * $env:ARBITER_BENCH = "1"
 * $env:ARBITER_BENCH_MODELS = "qwen3-vl:8b-instruct,gemma4:12b-it-qat"
 * mvn -q -pl classification-service test -Dtest=DocumentExtractionBenchmark
 * </pre>
 * The result lands in {@code docs/comparativa-modelos.md} and also goes to stdout.
 */
@EnabledIfEnvironmentVariable(named = "ARBITER_BENCH", matches = "1")
class DocumentExtractionBenchmark {

    /**
     * The two from the card. Override with {@code ARBITER_BENCH_MODELS} to add a third variant
     * without editing this file (e.g. {@code gemma4:e4b-it-qat} on a smaller machine).
     */
    private static final String DEFAULT_MODELS = "qwen3-vl:8b-instruct,gemma4:12b-it-qat";

    /**
     * The <i>sinMarca</i> set and not {@code conMarcaDePrueba} on purpose: those PDFs carry a
     * "documento simulado" line in the footer, which the vision model reads and reports as a
     * finding. That measures how well it reads a disclaimer, not how well it reads a document.
     * It is not hypothetical — on case #29, two of the six reasons Qwen gave for rejecting the
     * claim were that line.
     *
     * <p>Narrow it to one scenario (e.g. {@code .../sinMarca/celulares/robo}) to keep a run short:
     * the whole set is 21 documents, and on CPU that is hours per model.
     */
    private static final String DEFAULT_DOCS = "../docs/postman/test-docs/sinMarca";

    private static final String DEFAULT_OUTPUT = "../docs/comparativa-modelos.md";

    /**
     * A hard token: starts with a digit and continues with whatever an identifier may carry (date,
     * amount, MAC and CUIT separators). A five-character floor drops the noise — "15", "2026",
     * "A4" — without losing any value a rule looks at.
     */
    private static final Pattern HARD_TOKEN = Pattern.compile("\\d[\\dA-Za-z.,:/-]{4,}");

    private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Test
    void compararModelos() throws IOException {
        Path docsRoot = Path.of(env("ARBITER_BENCH_DOCS", DEFAULT_DOCS));
        String baseUrl = env("OLLAMA_BASE_URL", "http://localhost:11434");
        List<String> models = List.of(env("ARBITER_BENCH_MODELS", DEFAULT_MODELS).split("\\s*,\\s*"));
        List<Path> documents = findPdfs(docsRoot);

        if (documents.isEmpty()) {
            throw new IllegalStateException("No hay PDFs bajo " + docsRoot.toAbsolutePath()
                    + ". Generá los fixtures: node docs/postman/test-docs/generar-fixtures.js --sin-marca");
        }

        System.out.printf("[bench] %d document(s) x %d model(s) against %s%n",
                documents.size(), models.size(), baseUrl);

        List<Result> results = new ArrayList<>();
        for (String model : models) {
            OllamaDocumentAnalyzer analyzer = analyzerFor(baseUrl, model);
            for (Path document : documents) {
                results.add(measure(analyzer, model, document, docsRoot));
            }
            // Drop the weights before the next model: on a 16 GB machine two resident models at
            // once is exactly the OOM that killed llama-server on 18/08, and the second one would
            // start measuring with whatever RAM the first left behind.
            unload(baseUrl, model);
        }

        String report = render(results, models, documents, docsRoot, baseUrl);
        Path output = Path.of(env("ARBITER_BENCH_OUT", DEFAULT_OUTPUT));
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.printf("[bench] written to %s%n", output.toAbsolutePath().normalize());
    }

    // --- Measurement ------------------------------------------------------

    private Result measure(OllamaDocumentAnalyzer analyzer, String model, Path document, Path root) {
        byte[] content = read(document);
        String name = root.relativize(document).toString().replace('\\', '/');
        Set<String> expected = hardTokens(pdfText(content));

        System.out.printf("[bench] %s :: %s — %d hard token(s) to find%n",
                model, name, expected.size());

        long start = System.nanoTime();
        DocumentExtraction extraction;
        String failure = null;
        try {
            extraction = analyzer.extract(content, "application/pdf");
        } catch (RuntimeException e) {
            // A model that doesn't support part of the protocol (the output schema, say) belongs in
            // the table as such — it shouldn't bring down the run for the others.
            extraction = null;
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        Result result = Result.of(model, name, elapsed, extraction, expected, failure);
        System.out.printf("[bench] %s :: %s — %s, OCR %d/%d%n",
                model, name, human(elapsed), result.found(), result.expectedCount());
        return result;
    }

    /** What the PDF actually says: the fixtures carry selectable text, not an image. */
    private String pdfText(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Lowercased and without trailing punctuation: which separator the model picks for an amount
     * isn't what's being measured — whether the digits are there is.
     */
    private Set<String> hardTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = HARD_TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT).replaceAll("[.,:/-]+$", "");
            if (token.length() >= 5) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    // --- Wiring the real analyzer -----------------------------------------

    private OllamaDocumentAnalyzer analyzerFor(String baseUrl, String model) throws IOException {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        OllamaProperties properties = new OllamaProperties(baseUrl, model, "bench");
        // Same num_ctx and num_predict as production (application.yml) — measuring with others
        // would be measuring something else.
        OllamaClient client = new OllamaClient(restClient(baseUrl), properties, mapper, 32768, 4096);
        return new OllamaDocumentAnalyzer(client, mapper,
                new ClassPathResource("prompts/extraccion-documento-v5.md"));
    }

    private RestClient restClient(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** {@code keep_alive: 0} asks Ollama to release the weights right away. */
    private void unload(String baseUrl, String model) {
        try {
            restClient(baseUrl).post()
                    .uri("/api/generate")
                    .body(Map.of("model", model, "keep_alive", 0))
                    .retrieve()
                    .toBodilessEntity();
            System.out.printf("[bench] %s unloaded%n", model);
        } catch (RuntimeException e) {
            System.out.printf("[bench] could not unload %s: %s%n", model, e.getMessage());
        }
    }

    // --- Report -----------------------------------------------------------

    private String render(List<Result> results, List<String> models,
                          List<Path> documents, Path root, String baseUrl) {
        StringBuilder md = new StringBuilder();
        md.append("# Comparativa de modelos — paso de extracción\n\n");
        md.append("> Generado por `DocumentExtractionBenchmark` el ")
                .append(LocalDateTime.now().format(RUN_STAMP))
                .append(". **No editar a mano:** se pisa en cada corrida.\n\n");
        md.append("Corpus: `").append(root).append("` · ").append(documents.size())
                .append(" documento(s) · Ollama en `").append(baseUrl).append("`.\n\n");
        md.append("El puntaje de OCR es la proporción de *tokens duros* del PDF ")
                .append("—IMEI, nº de actuación, importes, fechas, series— que el modelo transcribió. ")
                .append("Un token perdido es una regla que no puede comparar.\n\n");

        md.append("## Resumen\n\n");
        md.append("| Modelo | Tiempo total | Por documento | OCR | Hallazgos visuales | Fallos |\n");
        md.append("|---|---:|---:|---:|---:|---:|\n");
        for (String model : models) {
            List<Result> forModel = results.stream().filter(r -> r.model().equals(model)).toList();
            Duration total = forModel.stream()
                    .map(Result::elapsed).reduce(Duration.ZERO, Duration::plus);
            int found = forModel.stream().mapToInt(Result::found).sum();
            int expected = forModel.stream().mapToInt(Result::expectedCount).sum();
            int findings = forModel.stream().mapToInt(Result::findings).sum();
            long failures = forModel.stream().filter(Result::failed).count();
            md.append("| `").append(model).append("` | ").append(human(total))
                    .append(" | ").append(human(total.dividedBy(Math.max(1, forModel.size()))))
                    .append(" | ").append(percent(found, expected))
                    .append(" (").append(found).append("/").append(expected).append(")")
                    .append(" | ").append(findings)
                    .append(" | ").append(failures == 0 ? "—" : String.valueOf(failures))
                    .append(" |\n");
        }

        md.append("\n## Por documento\n\n");
        md.append("| Documento | Modelo | Tiempo | OCR | Chars | IMEI | Importe | Fecha | Hallazgos |\n");
        md.append("|---|---|---:|---:|---:|---|---:|---|---:|\n");
        for (Path document : documents) {
            String name = root.relativize(document).toString().replace('\\', '/');
            for (String model : models) {
                results.stream()
                        .filter(r -> r.model().equals(model) && r.document().equals(name))
                        .findFirst()
                        .ifPresent(r -> md.append(r.asRow()));
            }
        }

        List<Result> failed = results.stream().filter(Result::failed).toList();
        if (!failed.isEmpty()) {
            md.append("\n## Fallos\n\n");
            failed.forEach(r -> md.append("- `").append(r.model()).append("` · `")
                    .append(r.document()).append("`: ").append(r.failure()).append("\n"));
        }
        return md.toString();
    }

    private record Result(String model, String document, Duration elapsed, int found,
                          int expectedCount, int chars, int findings, String imei,
                          String amount, String date, String failure) {

        static Result of(String model, String document, Duration elapsed,
                         DocumentExtraction extraction, Set<String> expected, String failure) {
            if (extraction == null) {
                return new Result(model, document, elapsed, 0, expected.size(),
                        0, 0, "—", "—", "—", failure);
            }
            String transcription = extraction.transcription().toLowerCase(Locale.ROOT);
            int found = (int) expected.stream().filter(transcription::contains).count();
            DocumentExtraction.Fields fields = extraction.fields();
            return new Result(model, document, elapsed, found, expected.size(),
                    extraction.transcription().length(), extraction.visualFindings().size(),
                    orDash(fields.imei()), orDash(fields.amount()), orDash(fields.documentDate()),
                    null);
        }

        boolean failed() {
            return failure != null;
        }

        String asRow() {
            return "| `" + document + "` | `" + model + "` | " + human(elapsed)
                    + " | " + percent(found, expectedCount)
                    + " | " + chars + " | " + imei + " | " + amount + " | " + date
                    + " | " + findings + " |\n";
        }

        private static String orDash(Object value) {
            return value == null ? "—" : value.toString();
        }
    }

    // --- Helpers ----------------------------------------------------------

    private static List<Path> findPdfs(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String human(Duration duration) {
        long seconds = duration.toSeconds();
        return seconds < 60 ? seconds + " s" : (seconds / 60) + " m " + (seconds % 60) + " s";
    }

    private static String percent(int found, int total) {
        return total == 0 ? "—" : Math.round(100.0 * found / total) + " %";
    }
}
