package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * No sender field: it comes from the JWT, so nobody can write in someone else's name. Anything
 * longer than the cap belongs in an attachment, where it gets a type and gets analyzed.
 */
public record CaseMessageRequest(
        @NotBlank(message = "El mensaje no puede estar vacío.")
        @Size(max = 2000, message = "El mensaje no puede superar los 2000 caracteres.")
        String body
) {}
