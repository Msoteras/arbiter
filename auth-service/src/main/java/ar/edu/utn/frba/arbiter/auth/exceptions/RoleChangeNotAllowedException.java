package ar.edu.utn.frba.arbiter.auth.exceptions;

/**
 * A role change only swaps between analyst and referent: an insured's identity comes from the
 * insurer, and turning one into staff would open every case of the insurer to them.
 */
public class RoleChangeNotAllowedException extends RuntimeException {
    public RoleChangeNotAllowedException() {
        super("Solo se puede cambiar el rol entre Analista de siniestros y Referente de aseguradora");
    }
}
