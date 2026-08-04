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
 * Turns the strings a denuncia arrives with into the rows {@code cases} now points at.
 *
 * <p>Everything here fails with {@link UnresolvedCaseReferenceException} (422) instead of
 * degrading to free text: the wizard only offers policies already synced from the insurer's DB,
 * so an unresolvable value means the request didn't come from the wizard, or the sync hasn't run.
 * Storing it anyway is how a case ends up referring to a policy nobody can find.
 */
@Service
@RequiredArgsConstructor
public class CaseReferenceResolver {

    private final BranchRepository branchRepository;
    private final ClaimCauseRepository claimCauseRepository;
    private final PolicyRepository policyRepository;
    private final InsuredRepository insuredRepository;

    /** {@code claim_cause} is unique per {@code (branch_id, name)}, so the branch resolves first. */
    public ClaimCause resolveClaimCause(String branchName, String claimCauseName) {
        Branch branch = branchRepository.findByName(branchName)
                .orElseThrow(() -> new UnresolvedCaseReferenceException("branch", branchName));
        return claimCauseRepository.findByBranchIdAndName(branch.getId(), claimCauseName)
                .orElseThrow(() -> new UnresolvedCaseReferenceException(
                        "claim cause for branch '" + branchName + "'", claimCauseName));
    }

    public Policy resolvePolicy(String policyNumber) {
        return policyRepository.findByExternalPolicyNumber(policyNumber)
                .orElseThrow(() -> new UnresolvedCaseReferenceException("policy", policyNumber));
    }

    /** The request's {@code insuredId} is the person's DNI, which is UNIQUE on {@code insured}. */
    public Insured resolveInsured(String dni) {
        return insuredRepository.findByDni(dni)
                .orElseThrow(() -> new UnresolvedCaseReferenceException("insured", dni));
    }

    /**
     * Refreshes the declarative fields the denuncia form captures. They describe the person, not
     * the claim, so they live on {@code insured} — filing a case is simply the moment the insured
     * last stated them.
     */
    public Insured applyDeclaredDetails(Insured insured, CaseRequest request) {
        insured.setPep(Boolean.TRUE.equals(request.pep()));
        insured.setImageConsent(Boolean.TRUE.equals(request.imageConsent()));
        if (request.contactEmail() != null) {
            insured.setEmail(request.contactEmail());
        }
        if (request.contactPhone() != null) {
            insured.setPhone(request.contactPhone());
        }
        return insuredRepository.save(insured);
    }
}
