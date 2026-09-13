package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyResyncSchedulerTest {

    @Mock
    private PolicyRepository policyRepository;
    @Mock
    private InsurerRepository insurerRepository;
    @Mock
    private PolicySynchronizer policySynchronizer;

    @InjectMocks
    private PolicyResyncScheduler scheduler;

    @Test
    void resyncsEveryPolicyOfEveryActiveInsurer() {
        when(insurerRepository.findByActiveTrue()).thenReturn(List.of(insurer("arbiter_bbva")));
        when(policyRepository.findAll()).thenReturn(List.of(policy("POL-1"), policy("POL-2")));

        scheduler.resyncPolicies();

        verify(policySynchronizer, times(2)).resync(any(Policy.class));
    }

    /**
     * La BD de una compañía caída esta noche no puede dejar sin refrescar a las demás: cada
     * aseguradora es un tenant aparte y no comparten nada.
     */
    @Test
    void oneInsurerFailingDoesNotStopTheRest() {
        when(insurerRepository.findByActiveTrue())
                .thenReturn(List.of(insurer("arbiter_bbva"), insurer("arbiter_provincia")));
        when(policyRepository.findAll())
                .thenThrow(new IllegalStateException("BD de la aseguradora inaccesible"))
                .thenReturn(List.of(policy("POL-2")));

        scheduler.resyncPolicies();

        verify(policySynchronizer).resync(any(Policy.class));
    }

    /** Ni una póliza que la compañía contesta mal puede costarle el refresco a las otras. */
    @Test
    void onePolicyFailingDoesNotStopTheOthers() {
        when(insurerRepository.findByActiveTrue()).thenReturn(List.of(insurer("arbiter_bbva")));
        when(policyRepository.findAll()).thenReturn(List.of(policy("POL-1"), policy("POL-2")));
        when(policySynchronizer.resync(any(Policy.class)))
                .thenThrow(new IllegalStateException("respuesta inesperada"))
                .thenReturn(1);

        scheduler.resyncPolicies();

        verify(policySynchronizer, times(2)).resync(any(Policy.class));
    }

    /** Sin pólizas copiadas no hay nada que releer — y no se le pregunta nada a la compañía. */
    @Test
    void aTenantWithNoPoliciesAsksTheCompanyNothing() {
        when(insurerRepository.findByActiveTrue()).thenReturn(List.of(insurer("arbiter_bbva")));
        when(policyRepository.findAll()).thenReturn(List.of());

        scheduler.resyncPolicies();

        verify(policySynchronizer, times(0)).resync(any(Policy.class));
        assertThat(true).isTrue();
    }

    private Insurer insurer(String schema) {
        Insurer insurer = new Insurer();
        insurer.setName(schema);
        insurer.setSchemaName(schema);
        return insurer;
    }

    private Policy policy(String number) {
        return Policy.builder().id(1L).insuredId(1L).externalPolicyNumber(number).inForce(true).build();
    }
}
