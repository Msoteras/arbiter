package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import ar.edu.utn.frba.arbiter.rules.dto.CatalogOption;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchInUseException;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchNameConflictException;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchNotFoundException;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ClaimCauseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD of the caller's insurer's own branches: the table lives in the tenant schema, so nothing here
 * reaches another insurer's catalog.
 */
@Service
@RequiredArgsConstructor
public class BranchCatalogService {

    private static final Logger log = LoggerFactory.getLogger(BranchCatalogService.class);

    private final BranchRepository branchRepository;
    private final ClaimCauseRepository claimCauseRepository;

    @Transactional(readOnly = true)
    public List<CatalogOption> list() {
        return branchRepository.findAll(Sort.by("name")).stream()
                .map(branch -> new CatalogOption(branch.getId(), branch.getName()))
                .toList();
    }

    @Transactional
    public CatalogOption create(String name) {
        String clean = normalize(name);
        branchRepository.findByName(clean).ifPresent(existing -> {
            throw new BranchNameConflictException(clean);
        });
        // A renamed branch still answers to its original name in the insurer database, so that name
        // is taken too: two branches matching the same policies would make the match ambiguous.
        branchRepository.findByExternalName(clean).ifPresent(existing -> {
            throw new BranchNameConflictException(clean);
        });
        // Born under the name the insurer database uses; renaming later leaves this one alone.
        Branch saved = branchRepository.save(Branch.builder().name(clean).externalName(clean).build());
        log.info("[BranchCatalog] created — id={} name='{}'", saved.getId(), saved.getName());
        return new CatalogOption(saved.getId(), saved.getName());
    }

    @Transactional
    public CatalogOption rename(Long id, String name) {
        String clean = normalize(name);
        Branch branch = branchRepository.findById(id).orElseThrow(() -> new BranchNotFoundException(id));
        branchRepository.findByName(clean)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new BranchNameConflictException(clean);
                });
        branch.setName(clean);
        Branch saved = branchRepository.save(branch);
        log.info("[BranchCatalog] renamed — id={} name='{}'", saved.getId(), saved.getName());
        return new CatalogOption(saved.getId(), saved.getName());
    }

    @Transactional
    public void delete(Long id) {
        Branch branch = branchRepository.findById(id).orElseThrow(() -> new BranchNotFoundException(id));
        // Explicit check on claim causes to give a clear 409; the other references (coverages,
        // rules) are caught by the FK and translated in the catch.
        if (!claimCauseRepository.findByBranch_IdOrderByNameAsc(id).isEmpty()) {
            throw new BranchInUseException(id);
        }
        try {
            branchRepository.delete(branch);
            branchRepository.flush(); // surfaces the FK violation here so it becomes a 409
        } catch (DataIntegrityViolationException e) {
            throw new BranchInUseException(id);
        }
        log.info("[BranchCatalog] deleted — id={}", id);
    }

    private String normalize(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            throw new InvalidRuleConfigurationException("El nombre del ramo no puede estar vacío");
        }
        return clean;
    }
}
