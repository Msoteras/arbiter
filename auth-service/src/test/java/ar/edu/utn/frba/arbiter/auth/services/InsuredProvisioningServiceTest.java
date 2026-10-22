package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.dto.InsuredDirectoryEntry;
import ar.edu.utn.frba.arbiter.auth.dto.ProvisioningSummary;
import ar.edu.utn.frba.arbiter.auth.exceptions.InsuredEmailTakenException;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsuredProvisioningServiceTest {

    @Mock
    private InsuredDirectoryAdapter insuredDirectoryAdapter;

    @Mock
    private InsuredAccountProvisioner provisioner;

    @Mock
    private SendGridAdapter sendGridAdapter;

    @InjectMocks
    private InsuredProvisioningService service;

    @Test
    void anEmailThatAlreadyBelongsToAnotherInsured_isSkippedWithTheDniAndTheReason() {
        InsuredDirectoryEntry sharedEmail =
                new InsuredDirectoryEntry("30.111.222", "Roman", "Castillo", "familia@example.com", null);
        when(insuredDirectoryAdapter.findWithPoliciesInForce(anyString())).thenReturn(List.of(sharedEmail));
        when(provisioner.provisionOne(eq(sharedEmail), eq(1L))).thenThrow(new InsuredEmailTakenException());

        ProvisioningSummary summary = service.provision("arbiter_bbva", 1L);

        assertThat(summary.profilesCreated()).isZero();
        assertThat(summary.skipped())
                .containsExactly("familia@example.com (30.111.222): El email ya pertenece a otro asegurado.");
    }
}
