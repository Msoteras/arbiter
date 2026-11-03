package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.dto.DocumentRequirementItem;
import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;
import ar.edu.utn.frba.arbiter.common.models.entities.DocumentType;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimCause;
import ar.edu.utn.frba.arbiter.rules.dto.DocumentRequirementDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchNotFoundException;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.DocumentRequirement;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.DocumentRequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A branch's document schedule, keyed and edited by branch + claim cause. Keeps no history.
 *
 * <p>A document with a band is only for cases from that band up, and the band exists only after
 * classification scores the case. So the reads that run before it (the claim wizard, the engine's
 * missing-documentation gate) see the band-free documents alone; cases-service reads the whole
 * schedule to suggest the rest to the analyst.
 */
@Service
@RequiredArgsConstructor
public class DocumentRequirementService {

    private final DocumentRequirementRepository documentRequirementRepository;
    private final ClaimCauseRepository claimCauseRepository;
    private final BranchRepository branchRepository;
    private final DocumentTypeService documentTypes;
    private final OnboardingTracker onboardingTracker;

    /** The whole schedule, bands included. */
    @Transactional(readOnly = true)
    public List<DocumentRequirementItem> get(Long branchId, Long claimCauseId) {
        // One item per document: a duplicated row keeps the lower band, the stricter reading.
        Map<String, DocumentRequirementItem> byCode = new LinkedHashMap<>();
        for (DocumentRequirement requirement
                : documentRequirementRepository.findByBranch_IdAndClaimCause_Id(branchId, claimCauseId)) {
            DocumentRequirementItem item = new DocumentRequirementItem(
                    requirement.getDocumentType().getCode(), requirement.getRiskBand());
            byCode.merge(item.documentType(), item, DocumentRequirementService::lowerBand);
        }
        return List.copyOf(byCode.values());
    }

    private static DocumentRequirementItem lowerBand(DocumentRequirementItem a, DocumentRequirementItem b) {
        if (a.minRiskBand() == null || b.minRiskBand() == null) {
            return new DocumentRequirementItem(a.documentType(), null);
        }
        return a.minRiskBand().compareTo(b.minRiskBand()) <= 0 ? a : b;
    }

    /** The documents every case needs, as codes. */
    @Transactional(readOnly = true)
    public List<String> requiredForEveryCase(Long branchId, Long claimCauseId) {
        return get(branchId, claimCauseId).stream()
                .filter(item -> item.minRiskBand() == null)
                .map(DocumentRequirementItem::documentType)
                .toList();
    }

    /**
     * {@link #requiredForEveryCase} resolving the claim cause by name within a branch known by id —
     * what the engine has at hand (see {@code ClaimReport.claimCause()}). An empty list inside the
     * Optional is "this claim cause requires no documents"; an empty Optional is "there is no such
     * claim cause in this branch". The engine needs the distinction to know whether it may fall back
     * to its baseline.
     */
    @Transactional(readOnly = true)
    public Optional<List<String>> findByBranchIdAndClaimCauseName(Long branchId, String claimCauseName) {
        return claimCauseRepository.findByBranch_IdAndName(branchId, claimCauseName)
                .map(claimCause -> requiredForEveryCase(branchId, claimCause.getId()));
    }

    /**
     * {@link #requiredForEveryCase} by branch and claim cause names, which is what the insured and
     * the analyst have at hand. Unknown branch or claim cause ⇒ empty list.
     */
    @Transactional(readOnly = true)
    public List<String> getByBranchAndClaimCauseNames(String branchName, String claimCauseName) {
        return branchRepository.findByName(branchName)
                .flatMap(branch -> findByBranchIdAndClaimCauseName(branch.getId(), claimCauseName))
                .orElse(List.of());
    }

    /** The whole schedule by names, bands included. Unknown branch or claim cause ⇒ empty list. */
    @Transactional(readOnly = true)
    public List<DocumentRequirementItem> getWithBandsByNames(String branchName, String claimCauseName) {
        return branchRepository.findByName(branchName)
                .flatMap(branch -> claimCauseRepository.findByBranch_IdAndName(branch.getId(), claimCauseName)
                        .map(claimCause -> get(branch.getId(), claimCause.getId())))
                .orElse(List.of());
    }

    /** Rejects the whole list when one of the documents isn't among the branch's. */
    @Transactional
    public List<DocumentRequirementDto> upsert(Long branchId, Long claimCauseId, List<DocumentRequirementItem> items) {
        onboardingTracker.touch(OnboardingStep.DOCUMENTS, null);
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new BranchNotFoundException(branchId));
        ClaimCause claimCause = claimCauseRepository.findById(claimCauseId)
                .filter(cc -> cc.getBranch().getId().equals(branchId))
                .orElseThrow(() -> new InvalidRuleConfigurationException(
                        "El hecho generador " + claimCauseId + " no pertenece al ramo " + branchId + "."));

        // Last one wins on a repeated code, so the list sent is the schedule stored.
        Map<String, DocumentRequirementItem> byCode = new LinkedHashMap<>();
        items.forEach(item -> byCode.put(item.documentType(), item));
        List<DocumentType> types = documentTypes.requireOfBranch(branchId, List.copyOf(byCode.keySet()));

        documentRequirementRepository.deleteByBranch_IdAndClaimCause_Id(branchId, claimCauseId);
        List<DocumentRequirement> requirements = types.stream()
                .map(type -> DocumentRequirement.builder()
                        .documentType(type)
                        .riskBand(byCode.get(type.getCode()).minRiskBand())
                        .branch(branch)
                        .claimCause(claimCause)
                        .build())
                .toList();
        return documentRequirementRepository.saveAll(requirements).stream()
                .map(saved -> new DocumentRequirementDto(
                        saved.getId(), saved.getDocumentType().getCode(), saved.getClaimCause().getId(),
                        saved.getRiskBand()))
                .toList();
    }
}
