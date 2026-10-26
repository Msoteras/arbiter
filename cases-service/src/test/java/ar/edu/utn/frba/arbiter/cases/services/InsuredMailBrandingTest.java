package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerLogoRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.common.models.entities.InsurerLogo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsuredMailBrandingTest {

    private static final long BBVA = 7L;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    @Mock
    private InsurerRepository insurerRepository;

    @Mock
    private InsurerLogoRepository insurerLogoRepository;

    private InsuredMailBranding branding;

    @BeforeEach
    void resolveTenant() {
        branding = new InsuredMailBranding(insurerRepository, insurerLogoRepository,
                "https://arbiter-prod.up.railway.app/");
        TenantContext.set("arbiter_bbva");
        when(insurerRepository.findBySchemaName("arbiter_bbva")).thenReturn(Optional.of(
                Insurer.builder().id(BBVA).name("BBVA Seguros").schemaName("arbiter_bbva").build()));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void signsWithTheLogoByItsPublicUrl() {
        when(insurerLogoRepository.findById(BBVA))
                .thenReturn(Optional.of(new InsurerLogo(BBVA, PNG, "image/png", Instant.now())));

        assertThat(branding.signature()).contains(
                "src=\"https://arbiter-prod.up.railway.app/api/v1/auth/public/insurers/bbva/logo\"",
                "<p>BBVA Seguros</p>");
    }

    @Test
    void withoutALogoItSignsAsArbiter() {
        when(insurerLogoRepository.findById(BBVA)).thenReturn(Optional.empty());

        assertThat(branding.signature()).isEqualTo(InsuredMailBranding.DEFAULT_SIGNATURE);
    }

    @Test
    void anOlderSvgLogoSignsAsArbiter() {
        when(insurerLogoRepository.findById(BBVA))
                .thenReturn(Optional.of(new InsurerLogo(BBVA, PNG, "image/svg+xml", Instant.now())));

        assertThat(branding.signature()).isEqualTo(InsuredMailBranding.DEFAULT_SIGNATURE);
    }
}
