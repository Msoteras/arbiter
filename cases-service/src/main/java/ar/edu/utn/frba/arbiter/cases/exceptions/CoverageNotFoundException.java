package ar.edu.utn.frba.arbiter.cases.exceptions;

public class CoverageNotFoundException extends RuntimeException {

    public CoverageNotFoundException(Long coverageId) {
        super("No encontramos una cobertura con id " + coverageId);
    }
}
