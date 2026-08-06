package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.CoverageOption;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CoverageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Lists an insurer's coverages for a rama, to populate the referente's Fast Track rule selectors.
 * cases-service owns the Coverage table, so the catalog is served from here rather than duplicating
 * the entity in rules-service. The tenant schema is resolved from the JWT, so the list is already
 * scoped to the caller's insurer.
 */
@Service
@RequiredArgsConstructor
public class CoverageCatalogService {

    private final CoverageRepository coverageRepository;

    @Transactional(readOnly = true)
    public List<CoverageOption> listByBranch(Long branchId) {
        return coverageRepository.findByBranchIdOrderByNameAsc(branchId).stream()
                .map(coverage -> new CoverageOption(coverage.getId(), coverage.getName()))
                .toList();
    }
}
