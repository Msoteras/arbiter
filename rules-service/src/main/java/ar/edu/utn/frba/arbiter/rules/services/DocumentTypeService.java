package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.dto.DocumentTypeResponse;
import ar.edu.utn.frba.arbiter.common.models.entities.DocumentType;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchNotFoundException;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchDocumentTypeRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.DocumentTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The document catalog, whole or as one branch may ask for it. Read-only: both are loaded by
 * script.
 */
@Service
@RequiredArgsConstructor
public class DocumentTypeService {

    private final DocumentTypeRepository documentTypeRepository;
    private final BranchDocumentTypeRepository branchDocumentTypeRepository;
    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public List<DocumentTypeResponse> list() {
        return documentTypeRepository.findAll(Sort.by("name")).stream().map(DocumentTypeService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<DocumentTypeResponse> listForBranch(Long branchId) {
        return forBranch(branchId).stream().map(DocumentTypeService::toResponse).toList();
    }

    /**
     * The branch's rows for those codes, in the order given and without repeats. A code that isn't
     * among the branch's documents rejects the whole list: saving the rest would drop it silently.
     */
    @Transactional(readOnly = true)
    public List<DocumentType> requireOfBranch(Long branchId, Collection<String> codes) {
        Map<String, DocumentType> allowed = forBranch(branchId).stream()
                .collect(Collectors.toMap(DocumentType::getCode, Function.identity()));
        List<String> unknown = codes.stream().filter(code -> !allowed.containsKey(code)).distinct().toList();
        if (!unknown.isEmpty()) {
            throw new InvalidRuleConfigurationException(
                    "El ramo no admite estos documentos: " + String.join(", ", unknown) + ".");
        }
        return new LinkedHashSet<>(codes).stream().map(allowed::get).toList();
    }

    private List<DocumentType> forBranch(Long branchId) {
        if (!branchRepository.existsById(branchId)) {
            throw new BranchNotFoundException(branchId);
        }
        return branchDocumentTypeRepository.findDocumentTypesByBranchId(branchId);
    }

    private static DocumentTypeResponse toResponse(DocumentType type) {
        return new DocumentTypeResponse(type.getCode(), type.getName());
    }
}
