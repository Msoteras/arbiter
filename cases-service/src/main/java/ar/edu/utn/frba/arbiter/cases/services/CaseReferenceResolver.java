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
     * La póliza del snapshot local y, si todavía no está, la que trae la BD Aseguradora en el acto
     * (decisión #10). Denunciar sobre una póliza que la compañía tiene pero Arbiter no copió es un
     * caso normal —el portal lista las pólizas leyendo la compañía en vivo—, no un dato inválido:
     * lo que falta es la sincronización, y se hace acá. El 422 queda para lo que de verdad no
     * resuelve (ver {@link PolicySynchronizer}).
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
     * Refreshes the contact fields the denuncia form captures. PEP comes from the insurer's data
     * and image consent is captured during onboarding — neither belongs in the claim form anymore.
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
