package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CaseRequest(
        @NotBlank String branch,
        @NotBlank String product,
        @NotBlank String claimCause,
        @NotBlank String insuredItem,
        @NotBlank String insuredId,
        @NotBlank String policyNumber,
        @NotBlank String description,
        // Un siniestro no puede haber "ocurrido" en el futuro — docs/frontend-bugs-ux.md #16.
        // El front ya pone un max=hoy en el datepicker; esto es la regla real.
        @NotNull @PastOrPresent LocalDateTime eventDate,
        @NotBlank String eventLocation,
        /** Fecha/hora de la denuncia policial, si el hecho tuvo una. No todo siniestro la requiere. */
        LocalDateTime policeReportAt,
        BigDecimal claimedAmount,
        @NotNull Boolean pep,
        /**
         * Insured's consent to have claim images analyzed for fraud indicators (H0009):
         * internal reuse detection + web search. Declarative, same treatment as {@code pep}.
         */
        @NotNull Boolean imageConsent,
        String contactEmail,
        String contactPhone
) {
}