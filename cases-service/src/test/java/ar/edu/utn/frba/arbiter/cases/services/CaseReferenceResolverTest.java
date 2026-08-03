package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsuredRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Resolver de strings a FKs. Lo que se prueba acá es la decisión de que un valor que no matchea
 * **falla** (422) en vez de guardarse como texto libre: el wizard solo ofrece pólizas ya
 * sincronizadas, así que un valor que no resuelve es un error real, no un caso a tolerar.
 */
@ExtendWith(MockitoExtension.class)
class CaseReferenceResolverTest {

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private ClaimCauseRepository claimCauseRepository;

    @Mock
    private PolicyRepository policyRepository;

    @Mock
    private InsuredRepository insuredRepository;

    @InjectMocks
    private CaseReferenceResolver resolver;

    @Test
    void resolveClaimCause_findsItWithinItsBranch() {
        Branch branch = CaseFixtures.branch("Celulares");
        branch.setId(7L);
        ClaimCause cause = ClaimCause.builder().name("Robo en vía pública").branch(branch).build();
        when(branchRepository.findByName("Celulares")).thenReturn(Optional.of(branch));
        when(claimCauseRepository.findByBranchIdAndName(7L, "Robo en vía pública"))
                .thenReturn(Optional.of(cause));

        assertThat(resolver.resolveClaimCause("Celulares", "Robo en vía pública")).isSameAs(cause);
    }

    @Test
    void resolveClaimCause_unknownBranch_throws() {
        when(branchRepository.findByName("Naves espaciales")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveClaimCause("Naves espaciales", "Robo"))
                .isInstanceOf(UnresolvedCaseReferenceException.class)
                .hasMessageContaining("Naves espaciales");
    }

    @Test
    void resolveClaimCause_causeNotInThatBranch_throws() {
        // La misma causa existe en otro ramo: el par (branch, name) es lo que la identifica, así
        // que pedirla en el ramo equivocado tiene que fallar y no traer la del otro.
        Branch branch = CaseFixtures.branch("Celulares");
        branch.setId(7L);
        when(branchRepository.findByName("Celulares")).thenReturn(Optional.of(branch));
        when(claimCauseRepository.findByBranchIdAndName(7L, "Incendio")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveClaimCause("Celulares", "Incendio"))
                .isInstanceOf(UnresolvedCaseReferenceException.class)
                .hasMessageContaining("Incendio");
    }

    @Test
    void resolvePolicy_unsyncedPolicy_throws() {
        // El caso que motivó la decisión: una póliza que todavía no bajó de la BD Aseguradora.
        when(policyRepository.findByExternalPolicyNumber("POL-NUEVA")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolvePolicy("POL-NUEVA"))
                .isInstanceOf(UnresolvedCaseReferenceException.class)
                .hasMessageContaining("POL-NUEVA");
    }

    @Test
    void resolveInsured_unknownDni_throws() {
        when(insuredRepository.findByDni("99.999.999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveInsured("99.999.999"))
                .isInstanceOf(UnresolvedCaseReferenceException.class)
                .hasMessageContaining("99.999.999");
    }

    @Test
    void applyDeclaredDetails_writesPepConsentAndContactOntoThePerson() {
        Insured insured = CaseFixtures.insured("40.123.456", "Laura", "Fernández");
        when(insuredRepository.save(any(Insured.class))).thenAnswer(inv -> inv.getArgument(0));

        Insured updated = resolver.applyDeclaredDetails(insured, request(true, true,
                "laura@example.com", "11-5555-0000"));

        assertThat(updated.isPep()).isTrue();
        assertThat(updated.isImageConsent()).isTrue();
        assertThat(updated.getEmail()).isEqualTo("laura@example.com");
        assertThat(updated.getPhone()).isEqualTo("11-5555-0000");
    }

    @Test
    void applyDeclaredDetails_nullContact_keepsWhatTheInsuredAlreadyHad() {
        // El alta no borra un contacto ya cargado sólo porque el formulario vino sin él.
        Insured insured = CaseFixtures.insured("40.123.456", "Laura", "Fernández");
        insured.setEmail("previo@example.com");
        insured.setPhone("11-4444-0000");
        when(insuredRepository.save(any(Insured.class))).thenAnswer(inv -> inv.getArgument(0));

        Insured updated = resolver.applyDeclaredDetails(insured, request(false, false, null, null));

        assertThat(updated.getEmail()).isEqualTo("previo@example.com");
        assertThat(updated.getPhone()).isEqualTo("11-4444-0000");
        assertThat(updated.isPep()).isFalse();
    }

    private CaseRequest request(boolean pep, boolean imageConsent, String email, String phone) {
        return new CaseRequest(
                "Celulares", "Celular Protegido Básico", "Robo en vía pública",
                "Motorola Edge 50 Pro", "40.123.456", "POL-CEL-2024-001",
                "Me robaron el celular", LocalDateTime.of(2026, 6, 13, 19, 45),
                "Estación Congreso, CABA", null, new BigDecimal("150000"),
                pep, imageConsent, email, phone);
    }
}
