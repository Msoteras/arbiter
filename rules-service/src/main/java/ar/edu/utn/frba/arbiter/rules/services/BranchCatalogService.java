package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
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
 * CRUD of the branch catalog (global {@code arbiter_common.branch} table). It's the catalog shared
 * by every insurer: which branches exist, not which ones each sells (that's narrowed per tenant by
 * its coverages / document schedule). That's why creating or deleting a branch touches the global
 * catalog — the referente administers it, with a unique name.
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
        Branch saved = branchRepository.save(Branch.builder().name(clean).build());
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
        // Explicit check on claim causes (same common schema) to give a clear 409; the per-tenant
        // references (coverages, rules) are caught by the FK and translated in the catch.
        if (!claimCauseRepository.findByBranch_IdOrderByNameAsc(id).isEmpty()) {
            throw new BranchInUseException(id);
        }
        try {
            branchRepository.delete(branch);
            branchRepository.flush(); // fuerza el chequeo del FK ahora, para traducirlo a 409
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
