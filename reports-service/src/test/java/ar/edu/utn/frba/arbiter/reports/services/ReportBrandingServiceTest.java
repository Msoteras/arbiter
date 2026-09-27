package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ReportBranding;
import ar.edu.utn.frba.arbiter.reports.models.repositories.InsurerBrandingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.BBVA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportBrandingServiceTest {

    private final InsurerBrandingRepository insurerBrandingRepository =
            mock(InsurerBrandingRepository.class);
    private final ReportBrandingService service =
            new ReportBrandingService(insurerBrandingRepository);

    @AfterEach
    void clearTheTenant() {
        TenantContext.clear();
    }

    @Test
    void brandsTheReportWithTheInsurerThatOwnsTheSchema() {
        TenantContext.set("arbiter_bbva");
        when(insurerBrandingRepository.findBySchemaName("arbiter_bbva")).thenReturn(Optional.of(BBVA));

        assertThat(service.current()).isEqualTo(BBVA);
    }

    /**
     * No tenant means no insurer to name, and the common schema has no row to look for: asking would
     * only cost a query that can't answer.
     */
    @Test
    void withoutAResolvedTenant_fallsBackWithoutAsking() {
        assertThat(service.current()).isEqualTo(ReportBranding.UNKNOWN);
        verify(insurerBrandingRepository, never()).findBySchemaName(any());
    }

    @Test
    void whenTheInsurerCannotBeRead_theReportStillGetsAHeader() {
        TenantContext.set("arbiter_bbva");
        when(insurerBrandingRepository.findBySchemaName("arbiter_bbva")).thenReturn(Optional.empty());

        assertThat(service.current()).isEqualTo(ReportBranding.UNKNOWN);
    }
}
