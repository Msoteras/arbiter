package ar.edu.utn.frba.arbiter.cases.exceptions;

/**
 * A firm that already received derivations is deactivated, not deleted: deleting it would leave
 * assessments pointing to a missing row.
 */
public class ExpertFirmInUseException extends RuntimeException {

    public ExpertFirmInUseException(Long expertFirmId) {
        super("El perito " + expertFirmId + " ya tiene peritajes registrados: desactivalo en vez de borrarlo");
    }
}
