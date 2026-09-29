package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ReportBranding;
import ar.edu.utn.frba.arbiter.reports.models.repositories.InsurerBrandingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReportBrandingService {

    private final InsurerBrandingRepository insurerBrandingRepository;

    public ReportBranding current() {
        if (!TenantContext.isResolved()) {
            return ReportBranding.UNKNOWN;
        }
        return insurerBrandingRepository.findBySchemaName(TenantContext.get())
                .orElse(ReportBranding.UNKNOWN);
    }
}
