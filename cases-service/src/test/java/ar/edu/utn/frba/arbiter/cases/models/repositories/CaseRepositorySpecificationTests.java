package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
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

    private java.util.List<Case> seeded;

    @BeforeEach
    void seed() {
        seeded = caseRepository.saveAll(java.util.List.of(
                caseOf(CaseStatus.PENDING_ANALYST_REVIEW, "Robo en vía pública", "POL-CEL-2024-001",
                        "40.123.456", "Laura Fernández", LocalDate.of(2026, 6, 1), RiskBand.LOW),
                caseOf(CaseStatus.PENDING_CLASSIFICATION, "Hurto", "POL-CEL-2024-002",
                        "40.123.457", null, LocalDate.of(2026, 6, 15), null),
                caseOf(CaseStatus.APPROVED, "Robo en vía pública", "POL-CEL-2024-003",
                        "40.123.456", "Laura Fernández", LocalDate.of(2026, 6, 30), RiskBand.HIGH),
                caseOf(CaseStatus.REJECTED, "Incendio", "POL-CEL-2024-004",
                        "40.123.458", "Marcos Díaz", LocalDate.of(2026, 7, 5), RiskBand.HIGH)
        ));
    }

    @Test
    void withoutFilters_doesNotThrow_andReturnsAllCases() {
        // Regression: Specification.allOf(...) exige que ningún elemento sea null; con todos los
        // filtros ausentes, withFilters() debía devolver null directo (no una lista con nulls).
        assertThatCode(() -> caseRepository.findAll(CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, null), FIRST_PAGE))
                .doesNotThrowAnyException();

        Page<Case> page = caseRepository.findAll(CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, null), FIRST_PAGE);

        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    void statusFilter_returnsOnlyMatchingStatus() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                CaseStatus.PENDING_ANALYST_REVIEW, null, null, null, null, null, null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getStatus() == CaseStatus.PENDING_ANALYST_REVIEW);
    }

    @Test
    void claimCauseAndInsuredId_combineAsAnd() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, "Robo en vía pública", null, "40.123.456", null, null, null, null);

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
                null, null, "POL-CEL-2024-002", null, null, null, null, null);

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
                null, null, null, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getContent())
                .noneMatch(c -> c.getPolicyNumber().equals("POL-CEL-2024-004")); // 5/jul, fuera de rango
    }

    @Test
    void eventDateTo_excludesTheDayAfter() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, LocalDate.of(2026, 6, 1), null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getPolicyNumber().equals("POL-CEL-2024-001"));
    }

    @Test
    void noMatchingFilters_returnsEmptyPage() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, "POL-INEXISTENTE", null, null, null, null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void freeTextSearch_matchesByPolicyNumberSubstring() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, "2024-002", null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getPolicyNumber().equals("POL-CEL-2024-002"));
    }

    @Test
    void freeTextSearch_matchesByInsuredIdSubstring() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, "123.457", null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getInsuredId().equals("40.123.457"));
    }

    @Test
    void freeTextSearch_matchesByInsuredName_caseInsensitive() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, "laura", null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        // Las dos cases sembradas con ese nombre, sin importar mayúsculas en el término buscado.
        assertThat(page.getContent())
                .hasSize(2)
                .allMatch(c -> "Laura Fernández".equals(c.getInsuredName()));
    }

    @Test
    void freeTextSearch_matchesById() {
        Long targetId = seeded.get(1).getId(); // el de policyNumber POL-CEL-2024-002
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, String.valueOf(targetId), null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent()).extracting(Case::getId).contains(targetId);
    }

    @Test
    void freeTextSearch_combinesWithStatusAsAnd() {
        // "2024-003" matchea policyNumber de un solo case (APPROVED); si combina mal con status
        // (OR en vez de AND), traería más de lo esperado.
        Specification<Case> spec = CaseSpecifications.withFilters(
                CaseStatus.APPROVED, null, null, null, null, null, "2024-003", null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getPolicyNumber().equals("POL-CEL-2024-003"));
    }

    @Test
    void freeTextSearch_noMatch_returnsEmptyPage() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, "no-existe-ningun-caso-asi", null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void riskBandFilter_returnsOnlyMatchingBand() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, RiskBand.HIGH);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(2)
                .allMatch(c -> c.getRiskBand() == RiskBand.HIGH);
    }

    @Test
    void riskBandFilter_combinesWithStatusAsAnd() {
        // Dos cases son HIGH; solo uno de ellos está además REJECTED. El AND no debe traer el otro.
        Specification<Case> spec = CaseSpecifications.withFilters(
                CaseStatus.REJECTED, null, null, null, null, null, null, RiskBand.HIGH);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getPolicyNumber().equals("POL-CEL-2024-004"));
    }

    private static Case caseOf(CaseStatus status, String claimCause, String policyNumber,
                                String insuredId, String insuredName, LocalDate eventDate, RiskBand riskBand) {
        return Case.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause(claimCause)
                .insuredItem("Samsung A56")
                .insuredId(insuredId)
                .insuredName(insuredName)
                .policyNumber(policyNumber)
                .description("Descripción de prueba")
                .eventDate(eventDate.atStartOfDay())
                .eventLocation("CABA")
                .status(status)
                .riskBand(riskBand)
                .build();
    }
}
