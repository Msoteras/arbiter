package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Lookup de pólizas para el alta de denuncia del asegurado. Delega en el
 * {@link InsurerAdapter} (BD Aseguradora, mock por ahora). Es la puerta del
 * asegurado a sus pólizas, incluidas las de distintas aseguradoras.
 */
@Service
@RequiredArgsConstructor
public class PolicyService {

    private final InsurerAdapter insurerAdapter;

    /** Pólizas del asegurado en todas las aseguradoras (vista centralizada). */
    public List<PolicyResponse> listByInsured(String insuredId) {
        return insurerAdapter.findPoliciesByInsured(insuredId);
    }

    public PolicyResponse getByNumber(String policyNumber) {
        return insurerAdapter.findPolicy(policyNumber)
                .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
    }
}
