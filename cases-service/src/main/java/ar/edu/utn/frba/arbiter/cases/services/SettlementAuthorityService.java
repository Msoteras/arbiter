package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.SettlementAuthorityResponse;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementAuthorityUpsertRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.SettlementAuthority;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.SettlementAuthorityRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The per-branch ceilings the referente configures (Anexo II attributions). Small on purpose: the
 * whole rule is "is this amount above the branch's limit", and the only thing worth centralizing
 * is that a branch with no row has no limit.
 */
@Service
@RequiredArgsConstructor
public class SettlementAuthorityService {

    private final SettlementAuthorityRepository authorityRepository;
    private final BranchRepository branchRepository;

    /**
     * One row per branch, including the branches with no ceiling — those come back with a null
     * {@code maxAmount}. The referente's screen has to list every ramo it can configure, not only
     * the ones already configured.
     */
    @Transactional(readOnly = true)
    public List<SettlementAuthorityResponse> list() {
        Map<Long, SettlementAuthority> byBranch = authorityRepository.findAll().stream()
                .collect(Collectors.toMap(SettlementAuthority::getBranchId, Function.identity()));

        return branchRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(Branch::getName))
                .map(branch -> {
                    SettlementAuthority authority = byBranch.get(branch.getId());
                    return new SettlementAuthorityResponse(
                            branch.getId(),
                            branch.getName(),
                            authority == null ? null : authority.getMaxAmount(),
                            authority == null ? null : authority.getUpdatedAt());
                })
                .toList();
    }

    /** Sets or clears a branch's ceiling. A null amount deletes the row — no limit, not a zero. */
    @Transactional
    public void set(Long branchId, SettlementAuthorityUpsertRequest request) {
        Optional<SettlementAuthority> existing = authorityRepository.findByBranchId(branchId);

        if (request.maxAmount() == null) {
            existing.ifPresent(authorityRepository::delete);
            return;
        }

        SettlementAuthority authority = existing.orElseGet(
                () -> SettlementAuthority.builder().branchId(branchId).build());
        authority.setMaxAmount(request.maxAmount());
        authority.setUpdatedAt(Instant.now());
        authorityRepository.save(authority);
    }

    /**
     * The ceiling in force for a branch, or null when it has none.
     *
     * <p>Read at the moment a settlement is confirmed and frozen onto it: the referente can raise
     * or lower this tomorrow, and a settlement already decided has to keep explaining itself with
     * the limit that actually sent it for authorization.
     */
    @Transactional(readOnly = true)
    public BigDecimal limitFor(Long branchId) {
        if (branchId == null) {
            return null;
        }
        return authorityRepository.findByBranchId(branchId)
                .map(SettlementAuthority::getMaxAmount)
                .orElse(null);
    }
}
