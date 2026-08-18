package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.ExpertFirmRequest;
import ar.edu.utn.frba.arbiter.cases.dto.ExpertFirmResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.ExpertFirmInUseException;
import ar.edu.utn.frba.arbiter.cases.exceptions.ExpertFirmNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertFirm;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ExpertAssessmentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ExpertFirmRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * El catálogo de peritos que administra el referente, y del que el analista elige al derivar.
 *
 * <p>Vive en cases-service y no en rules-service aunque se edite desde la pantalla de reglas: no es
 * una regla de negocio evaluable, es el directorio de proveedores que usa el módulo que deriva. El
 * umbral de monto —que sí es una regla— sí está en el motor.
 */
@Service
@RequiredArgsConstructor
public class ExpertFirmService {

    private final ExpertFirmRepository expertFirmRepository;
    private final ExpertAssessmentRepository expertAssessmentRepository;
    private final BranchRepository branchRepository;

    /** Todos, activos e inactivos: el referente administra el catálogo completo. */
    @Transactional(readOnly = true)
    public List<ExpertFirmResponse> list() {
        return expertFirmRepository.findAll().stream()
                .sorted(Comparator.comparing(ExpertFirm::getName, String.CASE_INSENSITIVE_ORDER))
                .map(ExpertFirmResponse::from)
                .toList();
    }

    @Transactional
    public ExpertFirmResponse create(ExpertFirmRequest request) {
        ExpertFirm firm = ExpertFirm.builder()
                .name(request.name().trim())
                .email(request.email().trim())
                .zone(blankToNull(request.zone()))
                .branch(resolveBranch(request.branchId()))
                .active(request.active())
                .build();
        return ExpertFirmResponse.from(expertFirmRepository.save(firm));
    }

    @Transactional
    public ExpertFirmResponse update(Long id, ExpertFirmRequest request) {
        ExpertFirm firm = expertFirmRepository.findById(id)
                .orElseThrow(() -> new ExpertFirmNotFoundException(id));
        firm.setName(request.name().trim());
        firm.setEmail(request.email().trim());
        firm.setZone(blankToNull(request.zone()));
        firm.setBranch(resolveBranch(request.branchId()));
        firm.setActive(request.active());
        return ExpertFirmResponse.from(expertFirmRepository.save(firm));
    }

    /**
     * Solo si nunca se usó. Un perito con peritajes se desactiva ({@code active = false}), que es
     * para lo que está la columna: deja de aparecer en el selector del analista sin borrar el
     * rastro de las derivaciones que ya recibió.
     */
    @Transactional
    public void delete(Long id) {
        ExpertFirm firm = expertFirmRepository.findById(id)
                .orElseThrow(() -> new ExpertFirmNotFoundException(id));
        if (expertAssessmentRepository.existsByExpertFirm_Id(id)) {
            throw new ExpertFirmInUseException(id);
        }
        expertFirmRepository.delete(firm);
    }

    /** null = generalista, cubre todos los ramos. Un id que no existe es un 422, no un null. */
    private Branch resolveBranch(Long branchId) {
        if (branchId == null) {
            return null;
        }
        return branchRepository.findById(branchId)
                .orElseThrow(() -> new UnresolvedCaseReferenceException("ramo", String.valueOf(branchId)));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
