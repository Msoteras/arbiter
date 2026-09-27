package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.ReportBranding;
import ar.edu.utn.frba.arbiter.reports.models.repositories.InsurerBrandingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** The identity the request's tenant signs its exported reports with. */
@Service
@RequiredArgsConstructor
public class ReportBrandingService {

    private final InsurerBrandingRepository insurerBrandingRepository;

    /**
     * Never null: an unresolved tenant or a missing insurer row still gets the neutral header, so the
     * layout is the same document either way.
     */
    public ReportBranding current() {
        if (!TenantContext.isResolved()) {
            return ReportBranding.UNKNOWN;
        }
        return insurerBrandingRepository.findBySchemaName(TenantContext.get())
                .orElse(ReportBranding.UNKNOWN);
    }
}
