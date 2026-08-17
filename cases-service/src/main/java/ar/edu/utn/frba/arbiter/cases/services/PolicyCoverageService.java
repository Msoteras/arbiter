package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository.PolicyCoverageOwner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Qué hechos generadores cubre una póliza, para el wizard de alta de denuncia: el asegurado ve los
 * hechos del ramo, y este servicio dice cuáles de esos su póliza SÍ cubre para marcar en rojo el
 * que no.
 *
 * <p>El dato vive en dos lados: el snapshot local {@code Policy} mapea póliza → {@code coverage.id}
 * (el mismo id que usa la regla {@code COVERAGE_INCLUSION}), y esa regla la administra el referente
 * en rules-service. Por eso resuelve la cobertura acá (dueño de la póliza) y delega la whitelist en
 * {@link RuleServiceClient} por REST interno.
 *
 * <p>Multi-tenant: la póliza puede ser de otra aseguradora que la del login (asegurado con pólizas
 * en dos compañías), así que primero ubica el esquema emisor con {@link PolicyTenantLocator} y lee
 * ahí — mismo criterio que el alta de denuncia.
 *
 * <p>La BD Arbiter está detrás de un enlace WAN (~1s por query), así que el camino se armó para
 * minimizar round-trips: una sola consulta trae {@code coverageId} + DNI del dueño juntos, y
 * rules-service resuelve los nombres en una query nativa. Antes eran 4 consultas donde ahora hay 2.
 */
@Service
@RequiredArgsConstructor
public class PolicyCoverageService {

    private final PolicyTenantLocator policyTenantLocator;
    private final PolicyRepository policyRepository;
    private final RuleServiceClient ruleServiceClient;

    public List<String> coveredClaimCauses(String policyNumber) {
        String issuingTenant = policyTenantLocator.locate(policyNumber);
        String callerTenant = TenantContext.get();
        TenantContext.set(issuingTenant);
        try {
            PolicyCoverageOwner policy = policyRepository.findCoverageAndOwner(policyNumber)
                    .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
            assertOwnPolicy(policy, policyNumber);

            Long coverageId = policy.getCoverageId();
            if (coverageId == null) {
                // Sin cobertura vinculada no hay whitelist que consultar: la póliza no cubre nada
                // (fail-closed, igual que una cobertura sin regla de inclusión).
                return List.of();
            }
            return ruleServiceClient.coveredClaimCauses(coverageId);
        } finally {
            TenantContext.set(callerTenant);
        }
    }

    /**
     * Un asegurado solo pregunta por SUS pólizas. 404 y no 403 sobre una ajena: un 403 confirmaría
     * que ese número existe. El referente no tiene DNI en el token (no hay fila {@code insured}); el
     * recorte ahí lo hace el conjunto de esquemas que {@link PolicyTenantLocator} sondea.
     */
    private void assertOwnPolicy(PolicyCoverageOwner policy, String policyNumber) {
        String callerDni = CallerContext.get().insuredId();
        if (callerDni != null && !callerDni.equals(policy.getOwnerDni())) {
            throw new PolicyNotFoundException(policyNumber);
        }
    }
}
