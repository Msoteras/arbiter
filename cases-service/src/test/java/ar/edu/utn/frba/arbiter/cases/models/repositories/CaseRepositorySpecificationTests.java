package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.dto.CaseScope;
import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Against real Postgres, not a mocked {@code CaseRepository}: {@link CaseSpecifications#withFilters}
 * may build a spec that looks right in Java, but only Hibernate running the Criteria API against a
 * real database exercises it.
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
    private PolicyCoverageRepository policyCoverageRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ClaimsAnalystRepository claimsAnalystRepository;

    @Autowired
    private ExpertAssessmentRepository expertAssessmentRepository;

    @PersistenceContext
    private EntityManager entityManager;

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
     * Same for the rest of the graph the case points at. Every FK is NOT NULL, so a case can't be
     * persisted until branch → claim cause, insured and coverage → policy all exist.
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
                    // insured.user_id is NOT NULL: identity lives in the common schema and the
                    // profile in the tenant's, so both ends must be created.
                    Insured person = CaseFixtures.insured(dni, name, surname);
                    person.setUser(userRepository.save(CaseFixtures.user(dni + "@example.com")));
                    return insuredRepository.save(person);
                });
    }

    private Policy policy(String policyNumber, Insured owner) {
        return policyRepository.findByExternalPolicyNumber(policyNumber)
                .orElseGet(() -> {
                    Policy policy = CaseFixtures.policy(policyNumber, "Celular Protegido Básico");
                                        policy.setInsuredId(owner.getId());
                    return policyRepository.save(policy);
                });
    }

    @BeforeEach
    void seed() {
        seeded = caseRepository.saveAll(java.util.List.of(
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
        // Specification.allOf(...) rejects null elements; with every filter absent, withFilters()
        // must return null itself (not a list of nulls).
        assertThatCode(() -> caseRepository.findAll(CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, null, null), FIRST_PAGE))
                .doesNotThrowAnyException();

        Page<Case> page = caseRepository.findAll(CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, null, null), FIRST_PAGE);

        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    /**
     * Mapping to {@code CaseResponse} runs with the session closed, so everything it navigates must
     * come in the query (e.g. {@code claimCause.branch}, which the {@code @EntityGraph}'s default
     * {@code FETCH} would leave lazy).
     */
    @Test
    void elListadoTraeTodoLoQueElMapeoNavegaConLaSesionCerrada() {
        // Before the query, or the seed leaves the graph in the cache and the test always passes.
        entityManager.flush();
        entityManager.clear();

        List<Case> porPagina = caseRepository.findAll(
                CaseSpecifications.withFilters(null, null, null, null, null, null, null, null, null),
                FIRST_PAGE).getContent();
        List<Case> porSort = caseRepository.findAll(
                CaseSpecifications.withFilters(null, null, null, "40.123.456", null, null, null, null, null),
                Sort.unsorted());
        entityManager.clear();

        assertThatCode(() -> Stream.concat(porPagina.stream(), porSort.stream()).forEach(entity -> {
            entity.getClaimCause().getName();
            entity.getClaimCause().getBranch().getName();
            entity.getInsured().getDni();
            entity.getPolicy().getExternalPolicyNumber();
            entity.getCoverage().getName();
            entity.getCurrentStatus().getName();
        })).doesNotThrowAnyException();
    }

    @Test
    void statusFilter_returnsOnlyMatchingStatus() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                List.of(CaseStatus.PENDING_ANALYST_REVIEW), null, null, null, null, null, null, null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getStatus() == CaseStatus.PENDING_ANALYST_REVIEW);
    }

    /** The insured's portal filters by bucket, and "in progress" spans four statuses. */
    @Test
    void statusFilter_acceptsSeveralStatuses() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                List.of(CaseStatus.PENDING_ANALYST_REVIEW, CaseStatus.APPROVED),
                null, null, null, null, null, null, null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .extracting(entity -> entity.getCurrentStatus().getName())
                .containsExactlyInAnyOrder(
                        CaseStatus.PENDING_ANALYST_REVIEW.name(), CaseStatus.APPROVED.name());
    }

    @Test
    void claimCauseAndInsuredId_combineAsAnd() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, "Robo en vía pública", null, "40.123.456", null, null, null, null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        // Both seeded cases with that cause belong to the same insured; the combined filter must
        // return exactly those.
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
        // Exact range over the first and last cases seeded in June: both ends must be included.
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), null, null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getContent())
                .noneMatch(c -> c.getPolicy().getExternalPolicyNumber().equals("POL-CEL-2024-004")); // July 5th, out of range
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

        // Both cases seeded with that name, regardless of the search term's case.
        assertThat(page.getContent())
                .hasSize(2)
                .allMatch(c -> "Laura Fernández".equals(c.getInsured().fullName()));
    }

    @Test
    void freeTextSearch_matchesByInsuredName_accentInsensitive() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, "fernandez", null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(2)
                .allMatch(c -> "Laura Fernández".equals(c.getInsured().fullName()));
    }

    @Test
    void freeTextSearch_matchesByFullName_accentInsensitive() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, "julio perez", null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> "Julio Pérez".equals(c.getInsured().fullName()));
    }

    @Test
    void freeTextSearch_matchesById() {
        Long targetId = seeded.get(1).getId(); // the one with policy number POL-CEL-2024-002
        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, String.valueOf(targetId), null, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent()).extracting(Case::getId).contains(targetId);
    }

    @Test
    void freeTextSearch_combinesWithStatusAsAnd() {
        // "2024-003" matches a single case's policy number (APPROVED); combining with status as OR
        // instead of AND would return more.
        Specification<Case> spec = CaseSpecifications.withFilters(
                List.of(CaseStatus.APPROVED), null, null, null, null, null, "2024-003", null, null);

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
        // Two cases are HIGH; only one of them is also REJECTED. The AND must not return the other.
        Specification<Case> spec = CaseSpecifications.withFilters(
                List.of(CaseStatus.REJECTED), null, null, null, null, null, null, RiskBand.HIGH, null);

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getPolicy().getExternalPolicyNumber().equals("POL-CEL-2024-004"));
    }

    @Test
    void analystFilter_returnsOnlyThatAnalystsCases() {
        // "Mine" lens: the first two seeded cases go to one analyst, the rest stay unassigned.
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
        // The analyst has two cases but only one is PENDING_ANALYST_REVIEW: crossing "Mine" with the
        // status filter must not return the other.
        ClaimsAnalyst owner = analyst("lucas.gomez@arbiter.test", "Lucas", "Gómez");
        assign(owner, seeded.get(0), seeded.get(1));

        Specification<Case> spec = CaseSpecifications.withFilters(
                List.of(CaseStatus.PENDING_ANALYST_REVIEW), null, null, null, null, null, null, null,
                owner.getId());

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(c -> c.getPolicy().getExternalPolicyNumber().equals("POL-CEL-2024-001"));
    }

    @Test
    void analystFilter_excludesUnassignedCases() {
        // An unassigned case belongs to nobody: it can't show up in any analyst's "Mine" lens. That's
        // what tells "unassigned" apart from "assigned to someone else".
        ClaimsAnalyst owner = analyst("lucas.gomez@arbiter.test", "Lucas", "Gómez");
        assign(owner, seeded.get(0));

        Specification<Case> spec = CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, null, owner.getId());

        assertThat(caseRepository.findAll(spec, FIRST_PAGE).getTotalElements()).isEqualTo(1);
    }

    @Test
    void openScope_leavesOutEveryClosedCase() {
        caseRepository.save(caseOf(CaseStatus.LAPSED, "Hurto", "POL-CEL-2024-005",
                "40.123.459", "Ana", "Sosa", LocalDate.of(2026, 5, 1), null));

        Page<Case> page = caseRepository.findAll(CaseSpecifications.scope(CaseScope.OPEN), FIRST_PAGE);

        assertThat(page.getContent())
                .extracting(entity -> entity.getCurrentStatus().getName())
                .containsExactlyInAnyOrder(
                        CaseStatus.PENDING_ANALYST_REVIEW.name(), CaseStatus.PENDING_CLASSIFICATION.name());
    }

    /**
     * A case past its art. 56 term is still open: it's the most urgent there is, not a closed one.
     * The only status reached by expiry is {@code LAPSED}, which is already closed.
     */
    @Test
    void openScope_keepsAnOverdueCase() {
        Case overdue = caseOf(CaseStatus.PENDING_ANALYST_REVIEW, "Hurto", "POL-CEL-2024-006",
                "40.123.460", "Bruno", "Vega", LocalDate.of(2026, 1, 10), null);
        overdue.setResponseDeadline(LocalDate.of(2026, 2, 9));
        caseRepository.save(overdue);

        Page<Case> page = caseRepository.findAll(CaseSpecifications.scope(CaseScope.OPEN), FIRST_PAGE);

        assertThat(page.getContent()).extracting(Case::getId).contains(overdue.getId());
    }

    /**
     * "Stalled" lens. Hibernate stamps {@code updatedAt} on save, so an old case has to be aged by
     * SQL: setting it on the entity would be overwritten by {@code @UpdateTimestamp} in the same flush.
     */
    @Test
    void staleSince_returnsOnlyOpenCasesNobodyTouched() {
        Case frozen = caseRepository.save(caseOf(CaseStatus.PENDING_ANALYST_REVIEW, "Hurto",
                "POL-CEL-2024-020", "40.123.470", "Rita", "Paz", LocalDate.of(2026, 1, 10), null));
        Case closedLongAgo = caseRepository.save(caseOf(CaseStatus.APPROVED, "Hurto",
                "POL-CEL-2024-021", "40.123.471", "Omar", "Gil", LocalDate.of(2026, 1, 10), null));
        Case justTouched = caseRepository.save(caseOf(CaseStatus.PENDING_ANALYST_REVIEW, "Hurto",
                "POL-CEL-2024-022", "40.123.472", "Sara", "Roca", LocalDate.of(2026, 1, 10), null));
        Instant longAgo = Instant.parse("2026-01-02T10:00:00Z");
        age(frozen.getId(), longAgo);
        age(closedLongAgo.getId(), longAgo);

        Page<Case> page = caseRepository.findAll(
                CaseSpecifications.staleSince(Instant.parse("2026-02-01T00:00:00Z")), FIRST_PAGE);

        assertThat(page.getContent()).extracting(Case::getId)
                // Closed months ago isn't stalled, it's finished; the one just touched isn't either.
                .containsExactly(frozen.getId())
                .doesNotContain(closedLongAgo.getId(), justTouched.getId());
    }

    @Test
    void returnedFromDerivation_keepsOnlyOpenCasesWithAProviderResponse() {
        ClaimsAnalyst lucas = analyst("derivations@arbiter.test", "Lucas", "Gómez");
        Instant answered = Instant.parse("2026-09-10T12:00:00Z");
        Case returned = caseRepository.save(caseOf(CaseStatus.PENDING_ANALYST_REVIEW, "Hurto",
                "POL-CEL-2024-030", "40.123.480", "Ana", "Sosa", LocalDate.of(2026, 9, 1), null));
        Case waitingAgain = caseRepository.save(caseOf(CaseStatus.PENDING_REPAIR, "Hurto",
                "POL-CEL-2024-031", "40.123.481", "Beto", "Luna", LocalDate.of(2026, 9, 1), null));
        Case closed = caseRepository.save(caseOf(CaseStatus.APPROVED, "Hurto",
                "POL-CEL-2024-032", "40.123.482", "Ciro", "Vera", LocalDate.of(2026, 9, 1), null));
        Case neverDerived = caseRepository.save(caseOf(CaseStatus.PENDING_ANALYST_REVIEW, "Hurto",
                "POL-CEL-2024-033", "40.123.483", "Dora", "Mena", LocalDate.of(2026, 9, 1), null));
        assessment(returned, ProviderType.ESTUDIO_LIQUIDADOR, answered, lucas);
        assessment(waitingAgain, ProviderType.ESTUDIO_LIQUIDADOR, answered, lucas);
        assessment(waitingAgain, ProviderType.SERVICIO_TECNICO, null, lucas);
        assessment(closed, ProviderType.ESTUDIO_LIQUIDADOR, answered, lucas);

        Page<Case> page = caseRepository.findAll(CaseSpecifications.returnedFromDerivation(true), FIRST_PAGE);

        assertThat(page.getContent()).extracting(Case::getId)
                .containsExactly(returned.getId())
                .doesNotContain(waitingAgain.getId(), closed.getId(), neverDerived.getId());
    }

    private void assessment(Case caseRecord, ProviderType providerType, Instant reportReceivedAt,
                            ClaimsAnalyst analyst) {
        expertAssessmentRepository.save(ExpertAssessment.builder()
                .caseId(caseRecord.getId())
                .expertName("North Assessors")
                .expertEmail("assessors@arbiter.test")
                .reason("Signs to verify")
                .derivedAt(Instant.parse("2026-09-05T12:00:00Z"))
                .providerType(providerType)
                .reportReceivedAt(reportReceivedAt)
                .derivedBy(analyst)
                .build());
    }

    private void age(Long caseId, Instant updatedAt) {
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE cases SET updated_at = :updatedAt WHERE id = :id")
                .setParameter("updatedAt", updatedAt)
                .setParameter("id", caseId)
                .executeUpdate();
        entityManager.clear();
    }

    @Test
    void closedScope_returnsOnlyTerminalStatuses() {
        Page<Case> page = caseRepository.findAll(CaseSpecifications.scope(CaseScope.CLOSED), FIRST_PAGE);

        assertThat(page.getContent())
                .extracting(entity -> entity.getCurrentStatus().getName())
                .containsExactlyInAnyOrder(CaseStatus.APPROVED.name(), CaseStatus.REJECTED.name());
    }

    @Test
    void allScope_doesNotRestrict() {
        assertThat(CaseSpecifications.scope(CaseScope.ALL)).isNull();
        assertThat(CaseSpecifications.scope(null)).isNull();
    }

    @Test
    void scopeCombinesWithTheRestOfTheFilters() {
        Specification<Case> spec = CaseSpecifications.withFilters(
                        null, "Robo en vía pública", null, null, null, null, null, null, null)
                .and(CaseSpecifications.scope(CaseScope.OPEN));

        Page<Case> page = caseRepository.findAll(spec, FIRST_PAGE);

        assertThat(page.getContent())
                .extracting(entity -> entity.getCurrentStatus().getName())
                .containsExactly(CaseStatus.PENDING_ANALYST_REVIEW.name());
    }

    /** The five counts come from a single aggregate query and must match counting each lens separately. */
    @Test
    void losConteosDeLasLentesDanIgualQueContarCadaUnaPorSeparado() {
        ClaimsAnalyst lucas = analyst("lucas.gomez@arbiter.test", "Lucas", "Gómez");
        assign(lucas, seeded.get(0), seeded.get(2));

        Specification<Case> base = CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, null, null);
        CaseLensCountRepository.LensCounts counts = caseRepository.countLenses(base, lucas.getId());

        assertThat(counts.all()).isEqualTo(caseRepository.count());
        assertThat(counts.mine()).isEqualTo(caseRepository.count(CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, null, lucas.getId())));
        assertThat(counts.assigned()).isEqualTo(caseRepository.count(CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, null, null, false, false, true)));
        assertThat(counts.unassigned()).isEqualTo(caseRepository.count(CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, null, null, true, false, false)));
        assertThat(counts.fraud()).isEqualTo(caseRepository.count(CaseSpecifications.withFilters(
                null, null, null, null, null, null, null, null, null, false, true, false)));
        assertThat(counts.assigned() + counts.unassigned()).isEqualTo(counts.all());
    }

    /** With no analyst profile in the tenant (the referent), "Mine" is 0, not everything. */
    @Test
    void sinAnalistaEnElTokenLosMiosSonCero() {
        CaseLensCountRepository.LensCounts counts = caseRepository.countLenses(
                CaseSpecifications.withFilters(null, null, null, null, null, null, null, null, null), null);

        assertThat(counts.mine()).isZero();
        assertThat(counts.all()).isEqualTo(4);
    }

    /** The filter bar narrows all five counts alike. */
    @Test
    void losConteosRespetanElRecorteYLosFiltros() {
        Specification<Case> soloEnCurso = CaseSpecifications.scope(CaseScope.OPEN);

        CaseLensCountRepository.LensCounts counts = caseRepository.countLenses(soloEnCurso, null);

        assertThat(counts.all()).isEqualTo(caseRepository.count(soloEnCurso));
        assertThat(counts.all()).isEqualTo(2);
    }

    /** The analyst lives in the tenant schema and its {@code user_id} is NOT NULL, like insured's. */
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
                .coverage(testCoverage())
                .description("Descripción de prueba")
                .occurredAt(eventDate.atStartOfDay())
                .eventAddress("CABA")
                .responseDeadline(eventDate.plusDays(30))
                .currentStatus(state(status))
                .riskBand(riskBand)
                .build();
    }

    private Coverage testCoverage() {
        return coverageRepository.findByName("Cobertura Celulares")
                .orElseGet(() -> coverageRepository.save(CaseFixtures.coverage("Celulares")));
    }

    /**
     * The sum insured lives in {@code policy_coverage}, not in {@code policy}: without this row the
     * policy has nothing to be evaluated against.
     */
    private Policy withCoverage(Policy policy) {
        policyCoverageRepository.save(CaseFixtures.policyCoverage(policy.getId(), testCoverage(), 1));
        return policy;
    }
}
