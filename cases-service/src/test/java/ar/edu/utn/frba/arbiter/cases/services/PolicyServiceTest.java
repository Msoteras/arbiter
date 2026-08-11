package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.InsuredIdentityMismatchException;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Que un asegurado solo llegue a sus propias pólizas (D31). El DNI llega como parámetro del
 * request, así que sin comparar contra el token cambiar un número devolvía nombre, mail, teléfono
 * y pólizas de cualquiera — el mismo agujero que D2, en otro endpoint.
 */
@ExtendWith(MockitoExtension.class)
class PolicyServiceTest {

    private static final String OWN_DNI = "42.987.654";
    private static final String SOMEONE_ELSE = "38.111.222";
    private static final String TENANT = "arbiter_bbva";

    @Mock
    private InsurerAdapter insurerAdapter;

    @InjectMocks
    private PolicyService policyService;

    @AfterEach
    void clearContext() {
        CallerContext.clear();
    }

    private void callerIsInsured(String dni) {
        CallerContext.set(new CallerContext.Caller(dni, List.of(1L), TENANT));
    }

    /** Analista y referente no tienen fila {@code insured}, así que su token no trae DNI. */
    private void callerIsNotAnInsured() {
        CallerContext.set(new CallerContext.Caller(null, List.of(1L), TENANT));
    }

    private PolicyResponse policyOf(String insuredId) {
        return PolicyResponse.builder()
                .policyNumber("POL-CEL-2026-042")
                .insuredId(insuredId)
                .insuredName("Martina Soteras")
                .build();
    }

    @Test
    void listingOwnPolicies_goesThrough() {
        callerIsInsured(OWN_DNI);
        when(insurerAdapter.findPoliciesByInsured(OWN_DNI)).thenReturn(List.of(policyOf(OWN_DNI)));

        assertThat(policyService.listByInsured(OWN_DNI)).hasSize(1);
    }

    @Test
    void listingSomeoneElsesPolicies_isRejected() {
        callerIsInsured(OWN_DNI);

        assertThatThrownBy(() -> policyService.listByInsured(SOMEONE_ELSE))
                .isInstanceOf(InsuredIdentityMismatchException.class);

        // No alcanza con que tire: no puede haber ido a buscarlas igual.
        verify(insurerAdapter, never()).findPoliciesByInsured(any());
    }

    /**
     * El referente consulta las de su compañía; el recorte ahí lo hace el conjunto de esquemas
     * que puede leer, no el DNI (que no tiene).
     */
    @Test
    void aCallerWithoutDni_isNotBoundByDni() {
        callerIsNotAnInsured();
        when(insurerAdapter.findPoliciesByInsured(SOMEONE_ELSE))
                .thenReturn(List.of(policyOf(SOMEONE_ELSE)));

        assertThat(policyService.listByInsured(SOMEONE_ELSE)).hasSize(1);
    }

    @Test
    void gettingOwnPolicyByNumber_goesThrough() {
        callerIsInsured(OWN_DNI);
        when(insurerAdapter.findPolicy("POL-CEL-2026-042"))
                .thenReturn(Optional.of(policyOf(OWN_DNI)));

        assertThat(policyService.getByNumber("POL-CEL-2026-042").insuredId()).isEqualTo(OWN_DNI);
    }

    /** 404 y no 403: un 403 confirmaría que ese número de póliza existe. */
    @Test
    void gettingSomeoneElsesPolicyByNumber_readsAsNotFound() {
        callerIsInsured(OWN_DNI);
        when(insurerAdapter.findPolicy("POL-CEL-2026-042"))
                .thenReturn(Optional.of(policyOf(SOMEONE_ELSE)));

        assertThatThrownBy(() -> policyService.getByNumber("POL-CEL-2026-042"))
                .isInstanceOf(PolicyNotFoundException.class);
    }

    @Test
    void unknownPolicy_isNotFound() {
        callerIsInsured(OWN_DNI);
        when(insurerAdapter.findPolicy("POL-NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> policyService.getByNumber("POL-NOPE"))
                .isInstanceOf(PolicyNotFoundException.class);
    }
}
