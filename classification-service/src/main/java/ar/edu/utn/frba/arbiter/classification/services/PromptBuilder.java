package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
public class PromptBuilder {

    private static final DateTimeFormatter EVENT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final String promptTemplate;

    public PromptBuilder(
            @Value("classpath:prompts/classification-v2.md") Resource promptResource
    ) throws IOException {
        this.promptTemplate = promptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    public String getPromptVersion() {
        return "classification-v2";
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

        String eventDate = request.eventDate() == null
                ? "No especificada"
                : EVENT_DATE_FORMAT.format(request.eventDate());

        String eventLocation = request.eventLocation() == null || request.eventLocation().isBlank()
                ? "No especificado"
                : request.eventLocation();

        // claimedAmount es nullable (el wizard no lo exige): sin dato va texto, no null — String.replace
        // no acepta reemplazo null.
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
                .replace("{{insuredHistory}}", history);
    }

    /** Monto reclamado con separador de miles (es-AR): 1234567 → "$1.234.567". */
    private static String formatAmount(BigDecimal amount) {
        return "$" + NumberFormat.getNumberInstance(Locale.of("es", "AR")).format(amount);
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
        sb.append("- Vigencia: %s a %s\n".formatted(policy.effectiveFrom(), policy.effectiveTo()));
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
