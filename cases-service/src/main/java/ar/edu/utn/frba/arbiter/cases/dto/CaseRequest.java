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
        // Un siniestro no puede haber "ocurrido" en el futuro. El front ya pone un max=hoy
        // en el datepicker; esto es la regla real.
        @NotNull @PastOrPresent LocalDateTime eventDate,
        /**
         * Dirección del hecho a nivel calle ("Av. Rivadavia 1234 (entre X e Y)"), SIN localidad ni
         * provincia: esas dos viajan aparte y se persisten en sus propias columnas.
         *
         * <p>Antes el wizard concatenaba las cuatro partes acá y mandaba {@code locality}/
         * {@code province} vacías, así que {@code cases.locality} y {@code cases.province} —que
         * existen en el esquema— quedaban en null y la ubicación no era consultable ni agrupable
         * (un reporte por provincia tendría que parsear prosa). El dato ya venía separado en el
         * formulario; lo único que faltaba era no aplastarlo.
         */
        @NotBlank String eventLocation,
        /** Provincia del hecho, tal como la eligió el asegurado en el wizard. */
        String province,
        /** Localidad del hecho. */
        String locality,
        /** Fecha/hora de la denuncia policial, si el hecho tuvo una. No todo siniestro la requiere. */
        LocalDateTime policeReportAt,
        BigDecimal claimedAmount,
        // PEP comes from the insurer's data, not the claim form. Ignored if sent.
        Boolean pep,
        // Image consent is captured during onboarding, not per claim. Ignored if sent.
        Boolean imageConsent,
        String contactEmail,
        String contactPhone
) {
}