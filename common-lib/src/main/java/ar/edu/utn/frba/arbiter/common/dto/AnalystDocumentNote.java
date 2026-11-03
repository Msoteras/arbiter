package ar.edu.utn.frba.arbiter.common.dto;

/**
 * What the analyst wrote when asking for a document the catalog doesn't name, so the model knows
 * what the attachment is and what to look for in it.
 *
 * @param type        the attachment's multipart key ({@code other_<n>})
 * @param description what the insured was asked for
 * @param reason      why the analyst asked for it; internal, never shown to the insured
 */
public record AnalystDocumentNote(String type, String description, String reason) {
}
