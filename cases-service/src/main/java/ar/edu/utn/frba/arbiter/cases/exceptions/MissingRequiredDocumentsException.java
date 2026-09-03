package ar.edu.utn.frba.arbiter.cases.exceptions;

import java.util.List;

/**
 * The denuncia doesn't carry every document the referente's schedule marks as required for that
 * branch + claim cause. 422 — the request is well formed, what doesn't hold is the schedule it's
 * filed against.
 *
 * <p>The schedule is the contract for "a complete case" (CLAUDE.md, AgendaDocumental), and until
 * now only the wizard enforced it: a client posting straight to this endpoint filed a theft with
 * no police report at all. A contract only the client checks isn't a contract.
 *
 * <p>The insured normally never gets here — the wizard disables the submit button and names what
 * is missing — so the message lists the raw document codes rather than Spanish labels. Those
 * labels live in the frontend by convention, and inventing a second copy here to serve a path
 * that's a guard rather than a screen would be the worse trade.
 */
public class MissingRequiredDocumentsException extends RuntimeException {

    private final List<String> missingDocumentTypes;

    public MissingRequiredDocumentsException(List<String> missingDocumentTypes) {
        super("Faltan documentos obligatorios para este tipo de siniestro: "
                + String.join(", ", missingDocumentTypes) + ".");
        this.missingDocumentTypes = List.copyOf(missingDocumentTypes);
    }

    public List<String> getMissingDocumentTypes() {
        return missingDocumentTypes;
    }
}
