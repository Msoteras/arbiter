package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsuredRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Turns the strings a claim is filed with into the rows {@code cases} points at.
 *
 * <p>Fails with {@link UnresolvedCaseReferenceException} (422) instead of degrading to free text:
 * storing an unresolvable value is how a case ends up referring to something nobody can find.
 */
@Service
@RequiredArgsConstructor
public class CaseReferenceResolver {

    private final BranchRepository branchRepository;
    private final ClaimCauseRepository claimCauseRepository;
    private final PolicyRepository policyRepository;
    private final InsuredRepository insuredRepository;
    private final PolicySynchronizer policySynchronizer;

    /** {@code claim_cause} is unique per {@code (branch_id, name)}, so the branch resolves first. */
    public ClaimCause resolveClaimCause(String branchName, String claimCauseName) {
        Branch branch = branchRepository.findByName(branchName)
                .orElseThrow(() -> new UnresolvedCaseReferenceException("branch", branchName));
        return claimCauseRepository.findByBranchIdAndName(branch.getId(), claimCauseName)
                .orElseThrow(() -> new UnresolvedCaseReferenceException(
                        "claim cause for branch '" + branchName + "'", claimCauseName));
    }

    /**
     * Falls back to importing the policy from the insurer's DB: the portal lists policies from the
     * insurer live, so one not yet snapshotted locally is a normal case, not invalid input.
     */
    public Policy resolvePolicy(String policyNumber, Long insuredId) {
        return policyRepository.findByExternalPolicyNumber(policyNumber)
                .orElseGet(() -> policySynchronizer.importFromInsurer(policyNumber, insuredId));
    }

    /** The request's {@code insuredId} is the person's DNI, which is UNIQUE on {@code insured}. */
    public Insured resolveInsured(String dni) {
        return insuredRepository.findByDni(dni)
                .orElseThrow(() -> new UnresolvedCaseReferenceException("insured", dni));
    }

    /**
     * Only the contact fields: PEP comes from the insurer's data and image consent from onboarding.
     */
    public Insured applyDeclaredDetails(Insured insured, CaseRequest request) {
        if (request.contactEmail() != null) {
            insured.setEmail(request.contactEmail());
        }
        if (request.contactPhone() != null) {
            insured.setPhone(request.contactPhone());
        }
        return insuredRepository.save(insured);
    }
}
