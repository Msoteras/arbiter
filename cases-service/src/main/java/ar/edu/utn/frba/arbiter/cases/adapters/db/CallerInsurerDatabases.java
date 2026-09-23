package ar.edu.utn.frba.arbiter.cases.adapters.db;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.common.tenant.InsurerDbSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * The insurer DB schemas the caller may read, one per insurer they belong to. Derived from the
 * signed {@code insurerIds} claim, never from request input, so nobody can name another insurer's schema.
 */
@Component
@RequiredArgsConstructor
public class CallerInsurerDatabases {

    /**
     * @param insurerId the platform's insurer id, not the insurer DB's {@code compania} id (always 1
     *                  with one schema per company)
     */
    public record InsurerDatabase(Long insurerId, String insurerName, String schema) {
    }

    private final InsurerRepository insurerRepository;

    /** Sorted by name so the listing is stable across calls. */
    public List<InsurerDatabase> forCaller() {
        List<Long> insurerIds = CallerContext.get().insurerIds();
        List<Insurer> insurers = insurerIds.isEmpty()
                // Without the claim (no user behind the call), fall back to the resolved tenant.
                ? insurerRepository.findBySchemaName(TenantContext.get()).stream().toList()
                : insurerRepository.findAllById(insurerIds);

        return insurers.stream()
                .filter(Insurer::isActive)
                .sorted(Comparator.comparing(Insurer::getName))
                .map(insurer -> new InsurerDatabase(
                        insurer.getId(),
                        insurer.getName(),
                        InsurerDbSchema.forTenant(insurer.getSchemaName())))
                .toList();
    }
}
