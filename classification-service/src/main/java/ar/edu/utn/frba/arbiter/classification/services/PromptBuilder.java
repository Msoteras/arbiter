package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;

import java.util.Objects;

public class PromptBuilder {

    private BusinessRules rules;
    private InsuredPolicy policy;
    private InsuredHistory history;

    public PromptBuilder withRules(BusinessRules rules) {
        this.rules = rules;
        return this;
    }

    public PromptBuilder withPolicy(InsuredPolicy policy) {
        this.policy = policy;
        return this;
    }

    public PromptBuilder withHistory(InsuredHistory history) {
        this.history = history;
        return this;
    }

    public String buildRulesAndPolicy() {
        Objects.requireNonNull(rules, "Rules cannot be null");
        Objects.requireNonNull(policy, "Policy cannot be null");

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

    public String buildHistory() {
        Objects.requireNonNull(history, "History cannot be null");

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
