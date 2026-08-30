package ar.edu.utn.frba.arbiter.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A message posted to a case's thread. No sender: it comes from the JWT, same as
 * {@link AnalystDecisionRequest} — a client-supplied one would let anyone write in someone
 * else's name. Capped at 2000 characters: this is a conversation, and anything longer belongs in
 * an attachment, which goes up the documentation screen where it gets a type and gets analyzed.
 */
public record CaseMessageRequest(
        @NotBlank(message = "El mensaje no puede estar vacío.")
        @Size(max = 2000, message = "El mensaje no puede superar los 2000 caracteres.")
        String body
) {}
