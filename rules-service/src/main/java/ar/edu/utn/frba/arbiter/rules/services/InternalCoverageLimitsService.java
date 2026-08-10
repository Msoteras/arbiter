package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.rules.dto.CoverageLimitsDto;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lectura system-to-system de los límites intrínsecos de una cobertura (plazo de denuncia, tope de
 * eventos por año) para el motor de clasificación — sin rol REFERENTE, con token de servicio que
 * lleva el tenant, igual que el resto de los {@code /internal/*}. Son columnas de {@code coverage};
 * la cobertura es del tenant, así que se leen de su esquema. Sin cobertura ⇒ vacío (el motor no
 * evalúa la regla y no se cae por falta de config).
 */
@Service
public class InternalCoverageLimitsService {

    private final CoverageRepository coverageRepository;

    public InternalCoverageLimitsService(CoverageRepository coverageRepository) {
        this.coverageRepository = coverageRepository;
    }

    @Transactional(readOnly = true)
    public CoverageLimitsDto getByCoverage(Long coverageId) {
        return coverageRepository.findById(coverageId)
                .map(coverage -> new CoverageLimitsDto(coverage.getReportDeadlineHours(), coverage.getMaxEventsPerYear()))
                .orElseGet(CoverageLimitsDto::empty);
    }
}
