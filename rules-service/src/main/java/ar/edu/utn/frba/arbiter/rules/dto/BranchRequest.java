package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta/renombre de un ramo: solo el nombre (el id es del catálogo). */
public record BranchRequest(
        @NotBlank @Size(max = 100) String name
) {}
