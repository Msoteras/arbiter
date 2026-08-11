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
 * CRUD del catálogo de ramos (tabla global {@code arbiter_common.branch}). Es el catálogo compartido
 * por todas las aseguradoras: qué ramos existen, no cuáles vende cada una (eso lo restringe cada
 * tenant con sus coberturas / agenda documental). Por eso crear o borrar un ramo toca el catálogo
 * global — lo administra el referente, con nombre único.
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
        // Chequeo explícito de los hechos generadores (mismo esquema común) para dar un 409 claro;
        // las referencias per-tenant (coberturas, reglas) las ataja el FK y se traducen en el catch.
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
