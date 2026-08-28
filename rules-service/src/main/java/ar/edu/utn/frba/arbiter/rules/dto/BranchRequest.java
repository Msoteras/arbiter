package ar.edu.utn.frba.arbiter.rules.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Creating or renaming a branch: just the name (the id belongs to the catalog). */
public record BranchRequest(
        @NotBlank @Size(max = 100) String name
) {}
