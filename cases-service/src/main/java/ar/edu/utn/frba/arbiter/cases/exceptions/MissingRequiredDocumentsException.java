package ar.edu.utn.frba.arbiter.cases.exceptions;

import java.util.List;

/**
 * Enforced server-side too, not only by the wizard. The message lists raw document codes: the wizard
 * already blocks this case, and the Spanish labels live in the frontend.
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
