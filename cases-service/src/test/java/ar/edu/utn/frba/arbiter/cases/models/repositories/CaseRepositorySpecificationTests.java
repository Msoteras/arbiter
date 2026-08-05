package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
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

    @Autowired
    private CaseStateRepository caseStateRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private ClaimCauseRepository claimCauseRepository;

    @Autowired
    private InsuredRepository insuredRepository;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private CoverageRepository coverageRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ClaimsAnalystRepository claimsAnalystRepository;

    private static final Pageable FIRST_PAGE = PageRequest.of(0, 20);

    private java.util.List<Case> seeded;

    /** {@code cases.current_status_id} is an FK: the catalog row has to exist first. */
    private CaseState state(CaseStatus status) {
        return caseStateRepository.findByName(status.name())
                .orElseGet(() -> caseStateRepository.save(CaseState.builder()
                        .name(status.name())
                        .description(status.name())
                        .insuredState("En análisis")
                        .isFinal(false)
                        .build()));
    }

    /**
     * Same for the rest of the graph the case now points at. Every FK is NOT NULL, so a case
     * can't be persisted until branch → claim cause, insured and coverage → policy all exist.
     */
    private ClaimCause claimCause(String branchName, String causeName) {
        Branch branch = branchRepository.findByName(branchName)
                .orElseGet(() -> branchRepository.save(CaseFixtures.branch(branchName)));
        return claimCauseRepository.findByBranchIdAndName(branch.getId(), causeName)
                .orElseGet(() -> claimCauseRepository.save(
                        ClaimCause.builder().name(causeName).branch(branch).build()));
    }

    private Insured insured(String dni, String name, String surname) {
        return insuredRepository.findByDni(dni)
                .orElseGet(() -> {
                    // insured.user_id es NOT NULL: la identidad vive en el esquema común y el
                    // perfil en el del tenant, así que hay que crear las dos puntas.
                    Insured person = CaseFixtures.insured(dni, name, surname);
                    person.setUser(userRepository.save(CaseFixtures.user(dni + "@example.com")));
                    return insuredRepository.save(person);
                });
    }

    private Policy policy(String policyNumber, Insured owner) {
        return policyRepository.findByExternalPolicyNumber(policyNumber)
                .orElseGet(() -> {
                    Policy policy = CaseFixtures.policy(policyNumber, "Celular Protegido Básico");
                    policy.setCoverage(coverageRepository.save(policy.getCoverage()));
                    policy.setInsuredId(owner.getId());
                    return policyRepository.save(policy);
                });
    }

    @BeforeEach
    void seed() {
        seeded = caseRepository.saveAll(java.util.List.of(
                // El asegurado ya no puede ser nulo: el nombre sale del join con `insured`, no de
                // la primera clasificación, así que todo caso nace con uno.
                caseOf(CaseStatus.PENDING_ANALYST_REVIEW, "Robo en vía pública", "POL-CEL-2024-001",
                        "40.123.456", "Laura", "Fernández", LocalDate.of(2026, 6, 1), RiskBand.LOW),
                caseOf(CaseStatus.PENDING_CLASSIFICATION, "Hurto", "POL-CEL-2024-002",
                        "40.123.457", "Julio", "Pérez", LocalDate.of(2026, 6, 15), null),
                caseOf(CaseStatus.APPROVED, "Robo en vía pública", "POL-CEL-2024-003",
                        "40.123.456", "Laura", "Fernández", LocalDate.of(2026, 6, 30), RiskBand.HIGH),
                caseOf(CaseStatus.REJECTED, "Incendio", "POL-CEL-2024-004",
                        "40.123.458", "Marcos", "Díaz", LocalDate.of(2026, 7, 5), RiskBand.HIGH)
        ));
    }

    @Test
    void withoutFilters_doesNotThrow_andReturnsAllCases() {
        // Regression: Specification.allOf(...) exige que ningún elemento sea null; con todos los
        // filtros ausentes, withFilters() debía devolver null directo (no una lista con nulls).
        assertThatCode(() -> caseRepository.findAll(CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, null, null), FIRST_PAGE))
                .doesNotThrowAnyException();

        Page<Case> page = caseRepository.findAll(CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, null, null), FIRST_PAGE);

        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    void statusFilter_returnsOnlyMatchingStatus() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                CaseStatus.PENDING_ANALYST_REVIEW, null, null, null, null, null, null, null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getStatus() == CaseStatus.PENDING_ANALYST_REVIEW);
    }

    @Test
    void claimCauseAndInsuredId_combineAsAnd() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, "Robo en vía pública", null, "40.123.456", null, null, null, null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        // Ambos casos con esa causa son del mismo asegurado en la data sembrada; el filtro
        // combinado no debe traer de más ni de menos.
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent())
                .allMatch(c -> c.getClaimCause().getName().equals("Robo en vía pública"))
                .allMatch(c -> c.getInsured().getDni().equals("40.123.456"));
    }

    @Test
    void policyNumberFilter_returnsExactMatch() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, "POL-CEL-2024-002", null, null, null, null, null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getPolicy().getExternalPolicyNumber().equals("POL-CEL-2024-002"));
    }

    @Test
    void eventDateRange_isInclusiveOnBothEnds() {
        // Rango exacto sobre el primer y el último caso sembrados en junio: los dos límites tienen
        // que incluirse, ni un día de más ni de menos.
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), null, null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getContent())
                .noneMatch(c -> c.getPolicy().getExternalPolicyNumber().equals("POL-CEL-2024-004")); // 5/jul, fuera de rango
    }

    @Test
    void eventDateTo_excludesTheDayAfter() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, LocalDate.of(2026, 6, 1), null, null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getPolicy().getExternalPolicyNumber().equals("POL-CEL-2024-001"));
    }

    @Test
    void noMatchingFilters_returnsEmptyPage() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, "POL-INEXISTENTE", null, null, null, null, null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void freeTextSearch_matchesByPolicyNumberSubstring() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, "2024-002", null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getPolicy().getExternalPolicyNumber().equals("POL-CEL-2024-002"));
    }

    @Test
    void freeTextSearch_matchesByInsuredIdSubstring() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, "123.457", null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getInsured().getDni().equals("40.123.457"));
    }

    @Test
    void freeTextSearch_matchesByInsuredName_caseInsensitive() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, "laura", null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        // Las dos cases sembradas con ese nombre, sin importar mayúsculas en el término buscado.
        assertThat(page.getContent())
                .hasSize(2)
                .allMatch(c -> "Laura Fernández".equals(c.getInsured().fullName()));
    }

    @Test
    void freeTextSearch_matchesById() {
        Long targetId = seeded.get(1).getId(); // el de policyNumber POL-CEL-2024-002
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, String.valueOf(targetId), null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent()).extracting(Case::getId).contains(targetId);
    }

    @Test
    void freeTextSearch_combinesWithStatusAsAnd() {
        // "2024-003" matchea policyNumber de un solo case (APPROVED); si combina mal con status
        // (OR en vez de AND), traería más de lo esperado.
        Specification<Case> spec = CaseSpecifications.withFilters(
                CaseStatus.APPROVED, null, null, null, null, null, "2024-003", null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getPolicy().getExternalPolicyNumber().equals("POL-CEL-2024-003"));
    }

    @Test
    void freeTextSearch_noMatch_returnsEmptyPage() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, "no-existe-ningun-caso-asi", null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void riskBandFilter_returnsOnlyMatchingBand() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, RiskBand.HIGH, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(2)
                .allMatch(c -> c.getRiskBand() == RiskBand.HIGH);
    }

    @Test
    void riskBandFilter_combinesWithStatusAsAnd() {
        // Dos cases son HIGH; solo uno de ellos está además REJECTED. El AND no debe traer el otro.
        Specification<Case> spec = CaseSpecifications.withFilters(
                CaseStatus.REJECTED, null, null, null, null, null, null, RiskBand.HIGH, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getPolicy().getExternalPolicyNumber().equals("POL-CEL-2024-004"));
    }

    @Test
    void analystFilter_returnsOnlyThatAnalystsCases() {
        // Lente "Míos": los dos primeros sembrados quedan de un analista, el resto sin asignar.
        ClaimsAnalyst owner = analyst("lucas.gomez@arbiter.test", "Lucas", "Gómez");
        assign(owner, seeded.get(0), seeded.get(1));

        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, null, owner.getId());

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(2)
                .allMatch(c -> c.getAnalyst().getId().equals(owner.getId()));
    }

    @Test
    void analystFilter_combinesWithStatusAsAnd() {
        // El analista tiene dos expedientes, pero solo uno está PENDING_ANALYST_REVIEW: cruzar la
        // lente "Míos" con el filtro de estado no puede traer el otro.
        ClaimsAnalyst owner = analyst("lucas.gomez@arbiter.test", "Lucas", "Gómez");
        assign(owner, seeded.get(0), seeded.get(1));

        Specification<Case> spec = CaseSpecifications.withFilters(
                CaseStatus.PENDING_ANALYST_REVIEW, null, null, null, null, null, null, null,
                owner.getId());

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getPolicy().getExternalPolicyNumber().equals("POL-CEL-2024-001"));
    }

    @Test
    void analystFilter_excludesUnassignedCases() {
        // Un expediente sin dueño no es de nadie: no puede aparecer en la lente "Míos" de ningún
        // analista. Es lo que distingue "sin asignar" de "asignado a otro".
        ClaimsAnalyst owner = analyst("lucas.gomez@arbiter.test", "Lucas", "Gómez");
        assign(owner, seeded.get(0));

        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, null, owner.getId());

        assertThat(caseRepository.findAll(spec, FIRST_PAGE).getTotalElements()).isEqualTo(1);
    }

    /** El analista vive en el esquema del tenant y su {@code user_id} es NOT NULL, igual que insured. */
    private ClaimsAnalyst analyst(String email, String name, String surname) {
        return claimsAnalystRepository.save(ClaimsAnalyst.builder()
                .name(name)
                .surname(surname)
                .email(email)
                .user(userRepository.save(CaseFixtures.user(email)))
                .build());
    }

    private void assign(ClaimsAnalyst analyst, Case... cases) {
        for (Case entity : cases) {
            entity.setAnalyst(analyst);
        }
        caseRepository.saveAll(java.util.List.of(cases));
    }

    private Case caseOf(CaseStatus status, String claimCause, String policyNumber, String insuredId,
                         String insuredName, String insuredSurname, LocalDate eventDate, RiskBand riskBand) {
        Insured owner = insured(insuredId, insuredName, insuredSurname);
        Policy policy = policy(policyNumber, owner);
        return Case.builder()
                .claimCause(claimCause("Celulares", claimCause))
                .declaredItem("Samsung A56")
                .insured(owner)
                .policy(policy)
                .coverage(policy.getCoverage())
                .description("Descripción de prueba")
                .occurredAt(eventDate.atStartOfDay())
                .eventAddress("CABA")
                .responseDeadline(eventDate.plusDays(30))
                .currentStatus(state(status))
                .riskBand(riskBand)
                .build();
    }
}
