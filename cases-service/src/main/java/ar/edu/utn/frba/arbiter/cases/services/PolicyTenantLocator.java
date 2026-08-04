package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

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
 */
@Service
@RequiredArgsConstructor
public class PolicyTenantLocator {

    private final InsurerRepository insurerRepository;
    private final PolicyRepository policyRepository;

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

        String callerTenant = TenantContext.get();
        try {
            for (Insurer insurer : insurerRepository.findAllById(insurerIds)) {
                if (!insurer.isActive()) {
                    continue;
                }
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
