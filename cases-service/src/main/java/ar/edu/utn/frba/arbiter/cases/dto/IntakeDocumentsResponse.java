package ar.edu.utn.frba.arbiter.cases.dto;

import java.util.List;

/**
 * The documents requested when the claim is filed: what Fast Track requires. The full document
 * schedule is requested later, only if the claim doesn't qualify for Fast Track.
 *
 * @param fastTrackOnly false when the insurer configured no Fast Track list and this falls back to
 *                      the full schedule, so the case never reaches the analyst without documents
 */
public record IntakeDocumentsResponse(List<String> documentTypes, boolean fastTrackOnly) {
}
