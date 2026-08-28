package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.InsuredIdentityMismatchException;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Lookup de pólizas para el alta de denuncia del asegurado. Delega en el
 * {@link InsurerAdapter} (BD Aseguradora). Es la puerta del asegurado a sus pólizas, incluidas
 * las de distintas aseguradoras.
 *
 * <p><b>Un asegurado solo ve las suyas.</b> El endpoint recibe el DNI como parámetro y eso no
 * alcanza para nada: se compara contra el del token, igual que el alta de denuncia (D2). Sin
 * esta comparación, cambiar un número en la URL devolvía nombre, mail, teléfono y pólizas de
 * cualquier persona de la plataforma.
 */
@Service
@RequiredArgsConstructor
public class PolicyService {

    private final InsurerAdapter insurerAdapter;

    /** Pólizas del asegurado en todas sus aseguradoras (vista centralizada). */
    public List<PolicyResponse> listByInsured(String insuredId) {
        assertOwnPolicies(insuredId);
        return insurerAdapter.findPoliciesByInsured(insuredId);
    }

    public PolicyResponse getByNumber(String policyNumber) {
        PolicyResponse policy = insurerAdapter.findPolicy(policyNumber)
                .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
        // 404 y no 403 sobre una póliza ajena: un 403 confirmaría que ese número existe.
        if (!isOwn(policy.insuredId())) {
            throw new PolicyNotFoundException(policyNumber);
        }
        return policy;
    }

    /**
     * El referente no tiene DNI en el token (no hay fila {@code insured} para él) y consulta las
     * pólizas de su propia compañía: el recorte ahí lo hace el conjunto de esquemas que puede
     * leer, no el DNI. Para el ASEGURADO, en cambio, el DNI del token es el único recorte.
     */
    private void assertOwnPolicies(String requestedInsuredId) {
        if (!isOwn(requestedInsuredId)) {
            throw new InsuredIdentityMismatchException(
                    "An insured can only list their own policies");
        }
    }

    private boolean isOwn(String insuredId) {
        String callerDni = CallerContext.get().insuredId();
        return callerDni == null || callerDni.equals(insuredId);
    }
}
