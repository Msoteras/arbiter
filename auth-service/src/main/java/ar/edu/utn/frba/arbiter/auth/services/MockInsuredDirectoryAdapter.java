package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.InsuredDirectoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default when the {@code insurer-db} profile is off (dev and tests with no insurer database).
 *
 * <p>Returns nothing rather than fixtures: this adapter feeds a flow that creates accounts and
 * sends mail, so inventing policyholders would have it provisioning people who do not exist. An
 * empty run reports zero and changes nothing, which is the honest answer when the source of truth
 * is not plugged in.
 */
@Component
public class MockInsuredDirectoryAdapter implements InsuredDirectoryAdapter {

    private static final Logger log = LoggerFactory.getLogger(MockInsuredDirectoryAdapter.class);

    @Override
    public List<InsuredDirectoryEntry> findWithPoliciesInForce(String insurerDbSchema) {
        log.warn("[InsuredDirectory] Perfil 'insurer-db' apagado — no hay BD Aseguradora que leer, "
                + "el alta masiva no va a encontrar asegurados (schema pedido: {})", insurerDbSchema);
        return List.of();
    }
}
