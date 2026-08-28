package ar.edu.utn.frba.arbiter.cases.support;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.User;

import java.math.BigDecimal;

/**
 * The rows a {@code Case} now points at, built in one place. Since the case stopped storing free
 * text, every test that needs one needs the whole little graph behind it — branch → claim cause,
 * insured, coverage → policy.
 *
 * <p>For unit tests with mocked repositories the ids are irrelevant; the persistence IT seeds
 * these through the real repositories instead, so nothing here assumes an id was assigned.
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
     * Sin {@code user}: la asociación es NOT NULL, así que un test de persistencia tiene que
     * asignarle uno con {@link #user(String)} ya guardado. Los tests con repositorios mockeados no
     * lo necesitan.
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
                .sumInsured(new BigDecimal("500000"))
                .inForce(true)
                .coverage(coverage("Celulares"))
                .build();
    }
}
