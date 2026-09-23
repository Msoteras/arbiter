package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.SettlementAuthorityResponse;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementAuthorityUpsertRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.SettlementAuthority;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.SettlementAuthorityRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * The per-branch settlement ceilings the referent configures. A branch with no row has no limit.
 */
@Service
@RequiredArgsConstructor
public class SettlementAuthorityService {

    private final SettlementAuthorityRepository authorityRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;

    /** One row per branch, including those with no ceiling (null {@code maxAmount}). */
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
                            authority == null ? null : authority.getUpdatedAt(),
                            authority == null ? null : authority.getUpdatedBy());
                })
                .toList();
    }

    /** A null amount deletes the row — no limit, not a zero. */
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
        authority.setUpdatedBy(currentUserId());
        authorityRepository.save(authority);
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        return userRepository.findByEmail(authentication.getName())
                .map(User::getId)
                .orElse(null);
    }

    /**
     * Null when the branch has none. Frozen onto the settlement when confirmed, so a later change
     * to the ceiling doesn't rewrite why a past settlement needed authorization.
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
