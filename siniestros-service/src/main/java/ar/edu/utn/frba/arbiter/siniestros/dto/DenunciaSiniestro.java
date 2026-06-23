package ar.edu.utn.frba.arbiter.siniestros.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Builder
public record DenunciaSiniestro(
        @NotBlank String branch,
        @NotBlank String product,
        @NotBlank String claimCause,
        @NotBlank String insuredItem,
        @NotBlank String insuredId,
        @NotBlank String policyNumber,
        @NotBlank String description,
        @NotNull LocalDateTime eventDate,
        @NotBlank String eventLocation,
        List<String> attachmentsOcr
) {}
