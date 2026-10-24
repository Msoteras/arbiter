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
import org.mockito.InjectMocks;
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

    @InjectMocks
    private InsuredMailBranding branding;

    @BeforeEach
    void resolveTenant() {
        TenantContext.set("arbiter_bbva");
        when(insurerRepository.findBySchemaName("arbiter_bbva"))
                .thenReturn(Optional.of(Insurer.builder().id(BBVA).schemaName("arbiter_bbva").build()));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void embedsTheLogoAboveTheBody() {
        when(insurerLogoRepository.findById(BBVA))
                .thenReturn(Optional.of(new InsurerLogo(BBVA, PNG, "image/png", Instant.now())));

        InsuredMailBranding.Branded mail = branding.brand("<p>Hola,</p>");

        assertThat(mail.htmlBody()).startsWith("<p><img src=\"cid:insurer-logo\"").endsWith("<p>Hola,</p>");
        assertThat(mail.attachments()).singleElement().satisfies(logo -> {
            assertThat(logo.contentId()).isEqualTo("insurer-logo");
            assertThat(logo.contentType()).isEqualTo("image/png");
        });
    }

    @Test
    void withoutALogoTheMailIsAsBefore() {
        when(insurerLogoRepository.findById(BBVA)).thenReturn(Optional.empty());

        InsuredMailBranding.Branded mail = branding.brand("<p>Hola,</p>");

        assertThat(mail.htmlBody()).isEqualTo("<p>Hola,</p>");
        assertThat(mail.attachments()).isEmpty();
    }

    @Test
    void anOlderSvgLogoIsLeftOut() {
        when(insurerLogoRepository.findById(BBVA))
                .thenReturn(Optional.of(new InsurerLogo(BBVA, PNG, "image/svg+xml", Instant.now())));

        assertThat(branding.brand("<p>Hola,</p>").attachments()).isEmpty();
    }
}
