package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimCause;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The claim cause catalog is per insurer. Someone insured at two companies is logged in under one
 * of them, so the selector has to read the catalog of whoever issued the chosen policy.
 */
@ExtendWith(MockitoExtension.class)
class ClaimCauseServiceTest {

    private static final String BBVA = "arbiter_bbva";
    private static final String PROVINCIA = "arbiter_provincia";
    private static final String PROVINCIA_POLICY = "POL-CEL-2026-777";

    @Mock
    private ClaimCauseRepository claimCauseRepository;
    @Mock
    private CaseReferenceResolver referenceResolver;
    @Mock
    private PolicyCoverageResolver policyCoverageResolver;
    @Mock
    private PolicyTenantLocator policyTenantLocator;

    @InjectMocks
    private ClaimCauseService service;

    /** Logged in under BBVA; no DNI in the caller, so the coverage exclusions are skipped. */
    @BeforeEach
    void loggedInUnderBbva() {
        TenantContext.set(BBVA);
        CallerContext.set(new CallerContext.Caller(null, List.of(1L, 2L), BBVA));
    }

    @AfterEach
    void clearContext() {
        CallerContext.clear();
        TenantContext.clear();
    }

    /** Provincia renamed its branch: the name only exists in its own schema. */
    @Test
    void readsTheCatalogOfTheInsurerThatIssuedThePolicy() {
        when(policyTenantLocator.locate(PROVINCIA_POLICY)).thenReturn(PROVINCIA);
        when(claimCauseRepository.findByBranch_NameOrderByNameAsc("Equipos Móviles"))
                .thenAnswer(invocation -> PROVINCIA.equals(TenantContext.get())
                        ? List.of(cause(3L, "Hurto"), cause(2L, "Robo en vía pública"))
                        : List.of());

        assertThat(service.namesByBranch("Equipos Móviles", PROVINCIA_POLICY))
                .containsExactly("Hurto", "Robo en vía pública");
    }

    /** Leaving the tenant switched would take the rest of the request, and the pooled connection, with it. */
    @Test
    void restoresTheCallersTenantAfterwards() {
        when(policyTenantLocator.locate(PROVINCIA_POLICY)).thenReturn(PROVINCIA);
        when(claimCauseRepository.findByBranch_NameOrderByNameAsc(anyString())).thenReturn(List.of());

        service.namesByBranch("Equipos Móviles", PROVINCIA_POLICY);

        assertThat(TenantContext.get()).isEqualTo(BBVA);
    }

    @Test
    void aPolicyThatCannotBeLocatedFallsBackToTheCallersCatalog() {
        when(policyTenantLocator.locate("POL-UNKNOWN"))
                .thenThrow(new UnresolvedCaseReferenceException("policy", "POL-UNKNOWN"));
        when(claimCauseRepository.findByBranch_NameOrderByNameAsc("Celulares"))
                .thenAnswer(invocation -> BBVA.equals(TenantContext.get())
                        ? List.of(cause(3L, "Hurto"))
                        : List.of());

        assertThat(service.namesByBranch("Celulares", "POL-UNKNOWN")).containsExactly("Hurto");
    }

    @Test
    void withoutAPolicyStaysInTheCallersTenant() {
        when(claimCauseRepository.findByBranch_NameOrderByNameAsc("Celulares"))
                .thenReturn(List.of(cause(3L, "Hurto")));

        assertThat(service.namesByBranch("Celulares", null)).containsExactly("Hurto");
        verifyNoInteractions(policyTenantLocator);
    }

    private static ClaimCause cause(Long id, String name) {
        ClaimCause cause = new ClaimCause();
        cause.setId(id);
        cause.setName(name);
        return cause;
    }
}
