package ar.edu.utn.frba.arbiter.cases.support;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.User;

import java.math.BigDecimal;

/**
 * The rows a {@code Case} points at (branch → claim cause, insured, coverage → policy), built in
 * one place. Nothing here assumes an id: persistence ITs save these through the real repositories.
 */
public final class CaseFixtures {

    private CaseFixtures() {
    }

    public static Branch branch(String name) {
        return Branch.builder().name(name).build();
    }

    public static ClaimCause claimCause(String branchName, String causeName) {
        return ClaimCause.builder().name(causeName).branch(branch(branchName)).build();
    }

    /**
     * No {@code user}: the association is NOT NULL, so a persistence test must set one already saved
     * with {@link #user(String)}.
     */
    public static Insured insured(String dni, String name, String surname) {
        return Insured.builder()
                .dni(dni)
                .name(name)
                .surname(surname)
                .build();
    }

    public static User user(String email) {
        return User.builder()
                .auth0Sub("auth0|" + email)
                .email(email)
                .build();
    }

    public static Coverage coverage(String branchName) {
        return Coverage.builder()
                .name("Cobertura " + branchName)
                .branchId(1L)
                .build();
    }

    public static Policy policy(String policyNumber, String product) {
        return Policy.builder()
                .externalPolicyNumber(policyNumber)
                .product(product)
                .inForce(true)
                .build();
    }

    /** Sum insured and deductible belong to the coverage, not the policy: a policy has several. */
    public static PolicyCoverage policyCoverage(Long policyId, Coverage coverage, int order) {
        return PolicyCoverage.builder()
                .policyId(policyId)
                .coverage(coverage)
                .displayOrder(order)
                .sumInsured(new BigDecimal("500000"))
                .deductiblePct(new BigDecimal("10.00"))
                .build();
    }
}
