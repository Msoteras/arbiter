package ar.edu.utn.frba.arbiter.classification.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Builder
public record ClassificationRequest(
        String branch,
        String product,
        String claimCause,
        String insuredItem,
        String description,
        LocalDateTime eventDate,
        String eventLocation,
        BigDecimal claimedAmount,
        List<String> attachmentsOcr,
        String insurerRules,
        String insuredHistory,
        // Veredicto de las reglas duras que el motor ya evaluó por código (cobertura del hecho, plazo
        // de denuncia, vigencia, tope de eventos). Se inyecta en el prompt como hecho establecido para
        // que el LLM no las re-decida (D4a paso 6). Vacío = sin incumplimientos.
        List<String> engineEvaluation
) {}
