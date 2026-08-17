package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.config.OllamaProperties;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Component
public class PromptBuilder {

    private static final DateTimeFormatter EVENT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final String promptTemplate;

    /**
     * The template is resolved <b>from</b> the configured version
     * ({@code arbiter.ollama.prompt-version}), which is what {@code ClassificationResultsService}
     * persists in {@code llm_analysis.prompt_version} for SSN Disposition 2/2023's audit. They used
     * to be two independent constants — the classpath here and the version in the yml — and they
     * already drifted once: the template was v2 and it was audited as v1. Now the file that gets
     * sent and the version that gets audited can't disagree, and bumping the version without
     * creating the file breaks at startup, which is when you want to find out.
     */
    public PromptBuilder(OllamaProperties properties, ResourceLoader resourceLoader) throws IOException {
        Resource promptResource =
                resourceLoader.getResource("classpath:prompts/" + properties.promptVersion() + ".md");
        this.promptTemplate = promptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    public String buildFullPrompt(ClassificationRequest request) {
        String attachmentsText = request.attachmentsOcr() == null || request.attachmentsOcr().isEmpty()
                ? "Sin documentos adjuntos"
                : String.join("\n\n---\n\n", request.attachmentsOcr());

        String history = request.insuredHistory() == null
                ? "Sin historial previo disponible"
                : request.insuredHistory();

        String rules = request.insurerRules() == null
                ? "Sin reglas adicionales configuradas"
                : request.insurerRules();

        String engineEvaluation = request.engineEvaluation() == null || request.engineEvaluation().isEmpty()
                ? "El motor no encontró incumplimientos de reglas duras (cobertura del hecho generador, "
                        + "plazo de denuncia, vigencia de la póliza, tope de eventos por año)."
                : request.engineEvaluation().stream().map(f -> "- " + f).reduce((a, b) -> a + "\n" + b).orElse("");

        String eventDate = request.eventDate() == null
                ? "No especificada"
                : EVENT_DATE_FORMAT.format(request.eventDate());

        String eventLocation = request.eventLocation() == null || request.eventLocation().isBlank()
                ? "No especificado"
                : request.eventLocation();

        // claimedAmount is nullable (the wizard doesn't require it): with no value it sends text,
        // not null — String.replace doesn't accept a null replacement.
        String claimedAmount = request.claimedAmount() == null
                ? "No declarado"
                : formatAmount(request.claimedAmount());

        return promptTemplate
                .replace("{{branch}}", request.branch())
                .replace("{{product}}", request.product())
                .replace("{{claimCause}}", request.claimCause())
                .replace("{{insuredItem}}", request.insuredItem())
                .replace("{{description}}", request.description())
                .replace("{{eventDate}}", eventDate)
                .replace("{{eventLocation}}", eventLocation)
                .replace("{{claimedAmount}}", claimedAmount)
                .replace("{{attachmentsOcr}}", attachmentsText)
                .replace("{{insurerRules}}", rules)
                .replace("{{engineEvaluation}}", engineEvaluation)
                .replace("{{insuredHistory}}", history);
    }

    /** Claimed amount with thousands separator (es-AR): 1234567 → "$1.234.567". */
    private static String formatAmount(BigDecimal amount) {
        return "$" + NumberFormat.getNumberInstance(Locale.of("es", "AR")).format(amount);
    }

    /**
     * One attachment, as the classifier sees it: the transcription and — separately, under its own
     * heading — what the vision pass observed in the image (D5).
     *
     * <p>The separation is the point: without a heading, "the signature is pixelated" reads as if
     * the document said it. The heading text tells the model where it came from and how much it
     * weighs; the classification prompt repeats it on the other side.
     */
    public String renderAttachment(String documentType, DocumentExtraction extraction) {
        String rendered = documentType + ": " + extraction.transcription();
        List<String> findings = extraction.visualFindings();
        if (findings.isEmpty()) {
            return rendered;
        }
        StringBuilder sb = new StringBuilder(rendered);
        sb.append("\n[Observado en la imagen de este adjunto, no es contenido del documento]\n");
        findings.forEach(finding -> sb.append("  - ").append(finding).append("\n"));
        return sb.toString().trim();
    }

    public String renderRulesAndPolicy(BusinessRules rules, InsuredPolicy policy) {
        var sb = new StringBuilder();

        sb.append("REGLAS DE LA ASEGURADORA (ramo: %s, hecho generador: %s):\n"
                .formatted(rules.branchId(), rules.claimCauseId()));
        rules.rules().forEach(r -> sb.append("- ").append(r).append("\n"));

        if (!rules.exclusions().isEmpty()) {
            sb.append("\nEXCLUSIONES DE COBERTURA:\n");
            rules.exclusions().forEach(e -> sb.append("- ").append(e).append("\n"));
        }

        if (!rules.fastTrackCriteria().isEmpty()) {
            sb.append("\nCRITERIOS FAST TRACK (si se cumplen todos, el caso es expedito):\n");
            rules.fastTrackCriteria().forEach(c -> sb.append("- ").append(c).append("\n"));
        }

        sb.append("\nDATOS DE LA PÓLIZA:\n");
        sb.append("- Número: %s\n".formatted(policy.policyNumber()));
        sb.append("- Estado de pago: %s\n".formatted(policy.upToDate() ? "Al día" : "CON MORA"));
        sb.append("- Vigencia: %s a %s\n".formatted(
                EVENT_DATE_FORMAT.format(policy.effectiveFrom()), EVENT_DATE_FORMAT.format(policy.effectiveTo())));
        sb.append("- Suma asegurada: $%s\n".formatted(policy.insuredAmount()));
        sb.append("- Franquicia: $%s\n".formatted(policy.deductible()));

        if (!policy.applicableClauses().isEmpty()) {
            sb.append("- Cláusulas: %s\n".formatted(String.join(", ", policy.applicableClauses())));
        }

        return sb.toString();
    }

    public String renderHistory(InsuredHistory history) {
        var sb = new StringBuilder();

        sb.append("HISTORIAL DEL ASEGURADO (DNI: %s)\n".formatted(history.insuredId()));
        sb.append("- Cliente desde: %s\n".formatted(history.customerSince()));
        sb.append("- Claims previos: %d\n".formatted(history.previousClaimsCount()));
        sb.append("- Monto total reclamado histórico: $%s\n".formatted(history.totalAmountClaimed()));

        if (history.claims().isEmpty()) {
            sb.append("\nSin claims previos registrados.");
        } else {
            sb.append("\nDETALLE DE SINIESTROS PREVIOS:\n");
            for (var c : history.claims()) {
                sb.append("\n  Claim %s — %s\n".formatted(c.claimId(), c.date()));
                sb.append("    Ramo: %s | Hecho: %s\n".formatted(c.branch(), c.claimCause()));
                sb.append("    Bien: %s\n".formatted(c.affectedItem()));
                sb.append("    Estado: %s | Reclamado: $%s | Liquidado: $%s\n"
                        .formatted(c.status(), c.amountClaimed(),
                                c.amountSettled() != null ? c.amountSettled() : "—"));
                if (c.notes() != null && !c.notes().isBlank()) {
                    sb.append("    Obs: %s\n".formatted(c.notes()));
                }
            }
        }

        return sb.toString();
    }
}
