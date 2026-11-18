package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.ExtractedField;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DocumentCorrectionRequest(
        @NotNull ExtractedField field,
        @Size(max = 100) String fieldName,
        @NotBlank @Size(max = 1000) String value) {}
