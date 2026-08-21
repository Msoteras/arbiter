package ar.edu.utn.frba.arbiter.cases.exceptions;

public class ExpertFirmNotFoundException extends RuntimeException {

    public ExpertFirmNotFoundException(Long expertFirmId) {
        super("No expert firm available for this case with id " + expertFirmId);
    }
}
