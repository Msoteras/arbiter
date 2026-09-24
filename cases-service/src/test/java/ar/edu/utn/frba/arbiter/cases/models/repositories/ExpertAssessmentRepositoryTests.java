package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.dto.RepairOutcome;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Transactional
class ExpertAssessmentRepositoryTests extends AbstractPersistenceIT {

    private static final Instant FIRST_REPORT = Instant.parse("2026-09-18T12:00:00Z");

    @Autowired private ExpertAssessmentRepository expertAssessmentRepository;
    @Autowired private ClaimsAnalystRepository claimsAnalystRepository;
    @Autowired private UserRepository userRepository;

    private ClaimsAnalyst analyst;

    @BeforeEach
    void setUp() {
        String email = "perito.tests@arbiter.test";
        analyst = claimsAnalystRepository.save(ClaimsAnalyst.builder()
                .name("Lucas")
                .surname("Gómez")
                .email(email)
                .user(userRepository.save(CaseFixtures.user(email)))
                .build());
    }

    @Test
    void caseWithoutDerivationsIsAbsent() {
        assertThat(expertAssessmentRepository.findRespondedByCaseIdIn(List.of(900_101L))).isEmpty();
    }

    @Test
    void derivationStillWaitingForTheProviderIsLeftOut() {
        save(900_102L, ProviderType.ESTUDIO_LIQUIDADOR, null, null, null);

        assertThat(expertAssessmentRepository.findRespondedByCaseIdIn(List.of(900_102L))).isEmpty();
    }

    @Test
    void respondedDerivationComesWithItsOutcomeAndDate() {
        save(900_103L, ProviderType.ESTUDIO_LIQUIDADOR, ExpertVerdict.FRAUD_CONFIRMED, null, FIRST_REPORT);

        assertThat(expertAssessmentRepository.findRespondedByCaseIdIn(List.of(900_103L)))
                .extracting(ExpertAssessmentRepository.RespondedDerivation::getCaseId,
                        ExpertAssessmentRepository.RespondedDerivation::getProviderType,
                        ExpertAssessmentRepository.RespondedDerivation::getVerdict,
                        ExpertAssessmentRepository.RespondedDerivation::getRepairOutcome,
                        ExpertAssessmentRepository.RespondedDerivation::getRespondedAt)
                .containsExactly(tuple(900_103L, ProviderType.ESTUDIO_LIQUIDADOR,
                        ExpertVerdict.FRAUD_CONFIRMED, null, FIRST_REPORT));
    }

    @Test
    void newestResponseComesFirstAndOneQueryCoversTheWholePage() {
        save(900_104L, ProviderType.ESTUDIO_LIQUIDADOR, ExpertVerdict.FRAUD_DISCARDED, null, FIRST_REPORT);
        save(900_104L, ProviderType.SERVICIO_TECNICO, null, RepairOutcome.REPAIRED,
                FIRST_REPORT.plus(1, ChronoUnit.DAYS));
        save(900_105L, ProviderType.SERVICIO_TECNICO, null, RepairOutcome.IRREPARABLE,
                FIRST_REPORT.plus(2, ChronoUnit.DAYS));

        assertThat(expertAssessmentRepository.findRespondedByCaseIdIn(List.of(900_104L, 900_105L)))
                .extracting(ExpertAssessmentRepository.RespondedDerivation::getCaseId,
                        ExpertAssessmentRepository.RespondedDerivation::getProviderType)
                .containsExactly(
                        tuple(900_105L, ProviderType.SERVICIO_TECNICO),
                        tuple(900_104L, ProviderType.SERVICIO_TECNICO),
                        tuple(900_104L, ProviderType.ESTUDIO_LIQUIDADOR));
    }

    private void save(Long caseId, ProviderType providerType, ExpertVerdict verdict,
                      RepairOutcome repairOutcome, Instant reportReceivedAt) {
        expertAssessmentRepository.save(ExpertAssessment.builder()
                .caseId(caseId)
                .expertName("Estudio Norte")
                .expertEmail("estudio@arbiter.test")
                .reason("Indicios a verificar")
                .derivedAt(FIRST_REPORT.minus(5, ChronoUnit.DAYS))
                .providerType(providerType)
                .verdict(verdict)
                .repairOutcome(repairOutcome)
                .reportReceivedAt(reportReceivedAt)
                .derivedBy(analyst)
                .build());
    }
}
