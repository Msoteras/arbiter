package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Qué aseguradora emitió la póliza que se está denunciando.
 *
 * <p>Existe porque el {@code tenantSchema} del JWT se resuelve en el login, cuando todavía no se
 * sabe sobre qué póliza va a denunciar el usuario. Para alguien con pólizas en dos aseguradoras
 * eso alcanzaba solo para la primera: denunciar un siniestro de la otra fallaba con 422, porque
 * la póliza no existe en ese esquema. El alta no puede depender de cuál aseguradora ganó el
 * sorteo en el login — tiene que salir del dato, y el dato es el número de póliza.
 *
 * <p>Busca <b>únicamente</b> entre los esquemas de {@code insurerIds}, un claim firmado del token.
 * Tomar la aseguradora de un parámetro del request dejaría que cualquiera escriba en el esquema
 * de otra compañía; el número de póliza es un dato del pedido, pero el conjunto donde se lo busca
 * no lo es.
 *
 * <p><b>La BD Aseguradora en vivo es la fuente de verdad, no el snapshot local.</b> Bug real del
 * 16/8: una póliza de Provincia (POL-CEL-2026-905) terminó denunciada bajo BBVA porque el
 * snapshot local (`arbiter_bbva.policy`) tenía una fila con ese mismo número — de una prueba
 * manual anterior, no de un caso real de BBVA — y el código de entonces confiaba en el PRIMER
 * esquema local que respondiera, sin volver a chequear contra la compañía. Un snapshot es una
 * caché de Arbiter, no una prueba de titularidad: si alguna vez queda desincronizado o duplicado
 * en el esquema equivocado, nada lo detectaba. Por eso ahora se pregunta primero a la aseguradora
 * (única fuente que sabe de verdad quién emitió la póliza) y el snapshot local queda como
 * fallback, solo para cuando la BD Aseguradora no responde.
 */
@Service
@RequiredArgsConstructor
public class PolicyTenantLocator {

    private final InsurerRepository insurerRepository;
    private final PolicyRepository policyRepository;
    private final InsurerAdapter insurerAdapter;

    /**
     * @return el esquema de la aseguradora que emitió la póliza
     * @throws UnresolvedCaseReferenceException 422, si ninguna de las aseguradoras del usuario la
     *         tiene — mismo criterio que el resto de las referencias que no resuelven
     */
    public String locate(String policyNumber) {
        List<Long> insurerIds = CallerContext.get().insurerIds();
        if (insurerIds.isEmpty()) {
            // Sin el claim (token viejo, o llamada sin usuario detrás) se opera contra el tenant
            // que ya venía resuelto: el comportamiento de antes, no un error nuevo.
            return TenantContext.get();
        }

        List<Insurer> insurers = insurerRepository.findAllById(insurerIds).stream()
                .filter(Insurer::isActive)
                .toList();

        // Sin ambigüedad no hace falta preguntarle a nadie: es la única aseguradora del asegurado.
        if (insurers.size() == 1) {
            return insurers.get(0).getSchemaName();
        }

        // Se pregunta primero a la aseguradora — el snapshot local puede tener una fila vieja o
        // duplicada en el esquema equivocado (ver javadoc de la clase), y confiar en él sin
        // contrastarlo es exactamente lo que rompió esto.
        Optional<String> fromInsurer = insurerAdapter.findPolicy(policyNumber)
                .map(PolicyResponse::insurerId)
                .flatMap(insurerId -> insurers.stream()
                        .filter(insurer -> String.valueOf(insurer.getId()).equals(insurerId))
                        .findFirst())
                .map(Insurer::getSchemaName);
        if (fromInsurer.isPresent()) {
            return fromInsurer.get();
        }

        // La compañía no la tiene (o no respondió): fallback al snapshot local, por si es una
        // póliza que Arbiter ya sincronizó bien antes y la BD Aseguradora está momentáneamente
        // inalcanzable. Best-effort a propósito — no vale la pena que un problema de red tire
        // abajo el alta de una póliza que ya se resolvió correctamente en el pasado.
        String callerTenant = TenantContext.get();
        try {
            for (Insurer insurer : insurers) {
                TenantContext.set(insurer.getSchemaName());
                if (policyRepository.findByExternalPolicyNumber(policyNumber).isPresent()) {
                    return insurer.getSchemaName();
                }
            }
        } finally {
            // Sondear no puede dejar el tenant movido: quien llama decide si lo cambia.
            TenantContext.set(callerTenant);
        }

        throw new UnresolvedCaseReferenceException("policy", policyNumber);
    }
}
