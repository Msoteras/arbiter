package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Con Postgres real (Testcontainers), no con {@code CaseRepository} mockeado. Existe porque un bug
 * real de esta historia —{@code Specification.allOf} tirando {@code IllegalArgumentException}
 * cuando no hay filtros, 500 en {@code GET /api/v1/cases} sin query params— no lo agarró ningún
 * test con mocks: {@link CaseSpecifications#withFilters} arma la spec correctamente en Java, pero
 * solo Hibernate ejecutando el Criteria API contra una BD real ejercita el camino que rompía.
 */
@SpringBootTest
@Transactional
class CaseRepositorySpecificationTests extends AbstractPersistenceIT {

    @Autowired
    private CaseRepository caseRepository;

    private static final Pageable FIRST_PAGE = PageRequest.of(0, 20);

    @BeforeEach
    void seed() {
        caseRepository.saveAll(java.util.List.of(
                caseOf(CaseStatus.PENDING_ANALYST_REVIEW, "Robo en vía pública", "POL-CEL-2024-001",
                        "40.123.456", LocalDate.of(2026, 6, 1)),
                caseOf(CaseStatus.PENDING_CLASSIFICATION, "Hurto", "POL-CEL-2024-002",
                        "40.123.457", LocalDate.of(2026, 6, 15)),
                caseOf(CaseStatus.APPROVED, "Robo en vía pública", "POL-CEL-2024-003",
                        "40.123.456", LocalDate.of(2026, 6, 30)),
                caseOf(CaseStatus.REJECTED, "Incendio", "POL-CEL-2024-004",
                        "40.123.458", LocalDate.of(2026, 7, 5))
        ));
    }

    @Test
    void withoutFilters_doesNotThrow_andReturnsAllCases() {
        // Regression: Specification.allOf(...) exige que ningún elemento sea null; con todos los
        // filtros ausentes, withFilters() debía devolver null directo (no una lista con nulls).
        assertThatCode(() -> caseRepository.findAll(CaseSpecifications.withFilters(
                null, null, null, null, null, null), FIRST_PAGE))
                .doesNotThrowAnyException();

        Page<Case> page = caseRepository.findAll(CaseSpecifications.withFilters(
                null, null, null, null, null, null), FIRST_PAGE);

        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    void statusFilter_returnsOnlyMatchingStatus() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                CaseStatus.PENDING_ANALYST_REVIEW, null, null, null, null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getStatus() == CaseStatus.PENDING_ANALYST_REVIEW);
    }

    @Test
    void claimCauseAndInsuredId_combineAsAnd() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, "Robo en vía pública", null, "40.123.456", null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        // Ambos casos con esa causa son del mismo asegurado en la data sembrada; el filtro
        // combinado no debe traer de más ni de menos.
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent())
                .allMatch(c -> c.getClaimCause().equals("Robo en vía pública"))
                .allMatch(c -> c.getInsuredId().equals("40.123.456"));
    }

    @Test
    void policyNumberFilter_returnsExactMatch() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, "POL-CEL-2024-002", null, null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getPolicyNumber().equals("POL-CEL-2024-002"));
    }

    @Test
    void eventDateRange_isInclusiveOnBothEnds() {
        // Rango exacto sobre el primer y el último caso sembrados en junio: los dos límites tienen
        // que incluirse, ni un día de más ni de menos.
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getContent())
                .noneMatch(c -> c.getPolicyNumber().equals("POL-CEL-2024-004")); // 5/jul, fuera de rango
    }

    @Test
    void eventDateTo_excludesTheDayAfter() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, LocalDate.of(2026, 6, 1));

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getPolicyNumber().equals("POL-CEL-2024-001"));
    }

    @Test
    void noMatchingFilters_returnsEmptyPage() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, "POL-INEXISTENTE", null, null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    private static Case caseOf(CaseStatus status, String claimCause, String policyNumber,
                                String insuredId, LocalDate eventDate) {
        return Case.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause(claimCause)
                .insuredItem("Samsung A56")
                .insuredId(insuredId)
                .policyNumber(policyNumber)
                .description("Descripción de prueba")
                .eventDate(eventDate.atStartOfDay())
                .eventLocation("CABA")
                .status(status)
                .build();
    }
}
