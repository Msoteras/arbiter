package ar.edu.utn.frba.arbiter.classification.adapters;

import ar.edu.utn.frba.arbiter.classification.config.GeminiProperties;
import ar.edu.utn.frba.arbiter.classification.config.LlmProperties;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.services.PromptBuilder;
import ar.edu.utn.frba.arbiter.common.enums.CauseConsistency;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibración del cruce relato ↔ hecho generador contra el modelo REAL (Gemini por Vertex).
 *
 * <p>No es un test de lógica — eso lo cubren {@code ClassificationOrchestratorCauseConsistencyTest}
 * y {@code ClaimClassifierImplTest} con el modelo mockeado. Lo que se mide acá es lo único que
 * ningún mock puede responder: <b>si el prompt está bien calibrado</b>. El riesgo del chequeo no es
 * que no detecte nada, es que detecte de más: "me robaron" es como la gente describe un robo, un
 * hurto y un olvido, y un detector con gatillo fácil manda a revisión denuncias honestas mal
 * narradas y le llena la bandeja de ruido al analista.
 *
 * <p>Por eso el banco tiene tantos <b>controles negativos</b> (relatos legítimos que NO se deben
 * marcar) como positivos. Los primeros son los que importan.
 *
 * <p>Fuera de la corrida normal ({@code @Tag("llm")}): pega contra Vertex, se factura por token y
 * necesita ADC. Para correrlo:
 *
 * <pre>
 *   gcloud auth application-default login          # una vez
 *   mvn -pl classification-service test -Dtest=CauseConsistencyCalibrationIT \
 *       -Dsurefire.failIfNoSpecifiedTests=false -Dgroups=llm
 * </pre>
 */
@Tag("llm")
class CauseConsistencyCalibrationIT {

    /** El catálogo del ramo Celulares, con Hurto excluido — igual que el seed. */
    private static final List<ClassificationRequest.ClaimCauseOption> CATALOG = List.of(
            new ClassificationRequest.ClaimCauseOption(1L, "Rotura accidental", true),
            new ClassificationRequest.ClaimCauseOption(2L, "Robo en vía pública", true),
            new ClassificationRequest.ClaimCauseOption(3L, "Hurto", false),
            new ClassificationRequest.ClaimCauseOption(4L, "Caída", true));

    private record Case(String name, String declaredCause, String account,
                        List<CauseConsistency> accepted, String expectedSuggestion) {}

    /**
     * Cada caso dice qué veredictos son ACEPTABLES, no uno solo. Un relato dudoso puede salir
     * AMBIGUOUS o MATCHES sin que nada esté mal; lo que no puede es salir CONTRADICTS.
     */
    private static final List<Case> BANK = List.of(
            // ── Controles negativos: relatos legítimos. Ninguno puede dar CONTRADICTS. ──
            new Case("robo con violencia explícita", "Robo en vía pública",
                    "Me abordaron dos personas en moto en la esquina de Corrientes y Medrano, uno me "
                            + "empujó contra la pared y me arrancó el celular de la mano.",
                    List.of(CauseConsistency.MATCHES), null),
            new Case("robo con arma", "Robo en vía pública",
                    "Un hombre me mostró un cuchillo y me exigió que le entregara el teléfono. Se lo di "
                            + "y salió corriendo.",
                    List.of(CauseConsistency.MATCHES), null),
            new Case("relato genérico: 'me robaron' y nada más", "Robo en vía pública",
                    "Me robaron el celular en la calle.",
                    List.of(CauseConsistency.MATCHES, CauseConsistency.AMBIGUOUS), null),
            new Case("relato corto sin circunstancias", "Robo en vía pública",
                    "Salí del trabajo y me quedé sin el teléfono.",
                    List.of(CauseConsistency.AMBIGUOUS), null),
            new Case("caída declarada y narrada", "Caída",
                    "Se me resbaló de la mano bajando las escaleras del subte y quedó con la pantalla "
                            + "partida.",
                    List.of(CauseConsistency.MATCHES), null),
            new Case("forcejeo sin arma ni golpe", "Robo en vía pública",
                    "Me tironearon la mochila, forcejeamos unos segundos y se llevaron el celular que "
                            + "estaba en el bolsillo de adelante.",
                    List.of(CauseConsistency.MATCHES), null),

            // ── Positivos: el relato describe otro hecho, y se puede citar la frase. ──
            new Case("hurto narrado bajo robo declarado", "Robo en vía pública",
                    "Estaba tomando algo en un bar de Palermo, dejé el celular sobre la mesa mientras "
                            + "iba al baño y cuando volví ya no estaba.",
                    List.of(CauseConsistency.CONTRADICTS), "Hurto"),
            new Case("carterista narrado bajo robo declarado", "Robo en vía pública",
                    "Me di cuenta al bajar del colectivo de que no tenía el celular. Iba lleno y "
                            + "alguien me lo sacó del bolsillo sin que yo lo notara.",
                    List.of(CauseConsistency.CONTRADICTS), "Hurto"),
            new Case("caída narrada bajo robo declarado", "Robo en vía pública",
                    "Se me cayó al piso mientras caminaba y se le rompió la pantalla entera.",
                    List.of(CauseConsistency.CONTRADICTS, CauseConsistency.AMBIGUOUS), null));

    @Test
    void calibration() {
        ClaimClassifier classifier = realClassifier();
        List<String> failures = new ArrayList<>();

        System.out.printf("%n%-45s │ %-12s │ %-22s │ %s%n",
                "CASO", "VEREDICTO", "SUGIERE", "CITA");
        System.out.println("─".repeat(130));

        for (Case testCase : BANK) {
            ClassificationResponse response = classifier.classify(request(testCase));
            CauseConsistency verdict = response.causeConsistency();
            boolean ok = testCase.accepted().contains(verdict);

            System.out.printf("%s %-43s │ %-12s │ %-22s │ %s%n",
                    ok ? "✓" : "✗",
                    testCase.name(),
                    verdict,
                    response.suggestedClaimCause() == null ? "—" : response.suggestedClaimCause(),
                    response.causeEvidence() == null ? "—" : truncate(response.causeEvidence()));

            if (!ok) {
                failures.add("%s: esperaba %s, salió %s"
                        .formatted(testCase.name(), testCase.accepted(), verdict));
            }
            // Una cita textual, no una paráfrasis: si el modelo la inventa, el analista lee una
            // frase que el asegurado nunca escribió.
            if (verdict == CauseConsistency.CONTRADICTS && response.causeEvidence() != null
                    && !testCase.account().contains(response.causeEvidence().trim())) {
                failures.add("%s: la cita no es textual del relato — «%s»"
                        .formatted(testCase.name(), response.causeEvidence()));
            }
            if (testCase.expectedSuggestion() != null && verdict == CauseConsistency.CONTRADICTS) {
                assertThat(response.suggestedClaimCause()).isEqualTo(testCase.expectedSuggestion());
            }
        }

        System.out.println("─".repeat(130));
        assertThat(failures)
                .as("Calibración del prompt %s — cada línea es un caso donde el modelo no se "
                        + "comportó como el prompt le pide", "classification-v5")
                .isEmpty();
    }

    private ClassificationRequest request(Case testCase) {
        return ClassificationRequest.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause(testCase.declaredCause())
                .insuredItem("Motorola Edge 50 Pro")
                .description(testCase.account())
                .eventDate(LocalDateTime.of(2026, 6, 13, 19, 45))
                .eventLocation("CABA")
                .claimedAmount(new BigDecimal("450000"))
                .claimCauseCatalog(CATALOG)
                .build();
    }

    /** El adapter real sobre Vertex, armado a mano: sin contexto de Spring ni base de datos. */
    private ClaimClassifier realClassifier() {
        GeminiProperties properties = new GeminiProperties(
                System.getenv().getOrDefault("GOOGLE_CLOUD_PROJECT", ""),
                System.getenv().getOrDefault("GOOGLE_CLOUD_LOCATION", "global"),
                System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.5-flash"),
                4096, 1000000);
        assertThat(properties.project())
                .as("GOOGLE_CLOUD_PROJECT sin definir — exportá las variables del .env antes de correr")
                .isNotBlank();

        Client genAi = Client.builder()
                .vertexAI(true)
                .project(properties.project())
                .location(properties.location())
                .build();

        try {
            return new ClaimClassifierImpl(
                    new GeminiClient(genAi, properties),
                    new ObjectMapper(),
                    new PromptBuilder(
                            new LlmProperties("gemini", "classification-v5"),
                            new org.springframework.core.io.DefaultResourceLoader()));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo construir el classifier real", e);
        }
    }

    private static String truncate(String value) {
        return value.length() <= 55 ? value : value.substring(0, 52) + "...";
    }
}
