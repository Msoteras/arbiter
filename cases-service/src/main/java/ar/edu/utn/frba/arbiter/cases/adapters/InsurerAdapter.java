package ar.edu.utn.frba.arbiter.cases.adapters;

import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a la BD Aseguradora para el portal del asegurado (patrón Adapter). La
 * implementación real puede ser por API de la aseguradora o por BD compartida
 * (ver CLAUDE.md: "la BD Aseguradora se accede directo desde quien la necesita").
 * Es multi-aseguradora: {@code findPoliciesByInsured} agrega las pólizas del asegurado
 * de todas las compañías a las que pertenece.
 *
 * <p>Ninguna implementación filtra por quién pregunta: el recorte por identidad es de
 * {@link ar.edu.utn.frba.arbiter.cases.services.PolicyService}, para que valga igual
 * contra el mock que contra la BD.
 */
public interface InsurerAdapter {

    Optional<PolicyResponse> findPolicy(String policyNumber);

    List<PolicyResponse> findPoliciesByInsured(String insuredId);
}
