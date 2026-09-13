package ar.edu.utn.frba.arbiter.rules.dto;

import java.util.List;

/**
 * Shape of the {@code configuration} JSONB on a {@code REPAIR_DERIVATION} rule.
 *
 * @param claimCauseIds the claim causes of the branch whose damaged item can go to a repair shop.
 *                      A stolen phone has nothing to repair, so which causes qualify is the
 *                      insurer's call, not the platform's.
 */
public record RepairDerivationConfig(List<Long> claimCauseIds) {}
