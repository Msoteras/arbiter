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
import static org.mockito.Mockito.lenient;
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
        // Lenient: the case links never read the insurer.
        lenient().when(insurerRepository.findBySchemaName("arbiter_bbva")).thenReturn(Optional.of(
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

    /** Case ids repeat across insurers: the link names the one the case belongs to. */
    @Test
    void linksTheCaseInTheInsuredPortalOfItsInsurer() {
        assertThat(branding.caseUrl(42L))
                .isEqualTo("https://arbiter-prod.up.railway.app/portal/cases/42?insurer=bbva");
    }

    /** The common schema is no insurer's: naming it would make the portal look in the wrong place. */
    @Test
    void withoutATenantTheLinkNamesNoInsurer() {
        TenantContext.clear();

        assertThat(branding.caseUrl(42L))
                .isEqualTo("https://arbiter-prod.up.railway.app/portal/cases/42");
    }

    @Test
    void linksTheAnalystToTheCaseScreen() {
        assertThat(branding.analystCaseUrl(42L))
                .isEqualTo("https://arbiter-prod.up.railway.app/cases/42");
    }

    @Test
    void linksEveryCaseNumberInTheText() {
        String html = InsuredMailBranding.linkCaseNumbers(
                "<p>El expediente #42 vence hoy. Revisá el #42.</p>", 42L, "https://a.test/cases/42?x=1&y=2");

        assertThat(html).isEqualTo("<p>El expediente <a href=\"https://a.test/cases/42?x=1&amp;y=2\">#42</a>"
                + " vence hoy. Revisá el <a href=\"https://a.test/cases/42?x=1&amp;y=2\">#42</a>.</p>");
    }

    @Test
    void leavesALongerNumberAlone() {
        assertThat(InsuredMailBranding.linkCaseNumbers("#420 y #4", 42L, "https://a.test/cases/42"))
                .isEqualTo("#420 y #4");
    }
}
