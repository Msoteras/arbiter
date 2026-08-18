package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.RulesAdapter;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.exceptions.FraudRecordAlreadyExistsException;
import ar.edu.utn.frba.arbiter.classification.exceptions.UnsupportedFraudRecordException;
import ar.edu.utn.frba.arbiter.classification.models.entities.InsuredFraudRecord;
import ar.edu.utn.frba.arbiter.classification.models.repositories.InsuredFraudRecordRepository;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordRequest;
import ar.edu.utn.frba.arbiter.common.dto.FraudRecordResponse;
import ar.edu.utn.frba.arbiter.common.enums.FraudRecordSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InsuredFraudRecordServiceTest {

    private static final String DNI = "40.123.456";

    private InsuredFraudRecordRepository repository;
    private RulesAdapter rulesAdapter;
    private InsuredFraudRecordService service;

    @BeforeEach
    void setUp() {
        repository = mock(InsuredFraudRecordRepository.class);
        rulesAdapter = mock(RulesAdapter.class);
        service = new InsuredFraudRecordService(repository, rulesAdapter);
        when(rulesAdapter.getFraudRecordPolicy()).thenReturn(activePolicy());
        when(repository.save(any())).thenAnswer(invocation -> {
            InsuredFraudRecord record = invocation.getArgument(0);
            record.setId(1L);
            record.setDeclaredAt(Instant.now());
            return record;
        });
    }

    @Test
    void registersAnExpertBackedRecord() {
        when(repository.findByCaseId(77L)).thenReturn(Optional.empty());

        FraudRecordResponse response = service.register(request(FraudRecordSource.EXPERT_BACKED, 5L));

        assertThat(response.source()).isEqualTo(FraudRecordSource.EXPERT_BACKED);
        assertThat(response.inForce()).isTrue();
        assertThat(response.scores()).isTrue();
    }

    /** Registered, visible, and still not counted: that's what "sin peritaje" buys. */
    @Test
    void anAnalystDeclaredRecordIsInForceButDoesNotScore() {
        when(repository.findByCaseId(77L)).thenReturn(Optional.empty());

        FraudRecordResponse response = service.register(request(FraudRecordSource.ANALYST_DECLARED, null));

        assertThat(response.inForce()).isTrue();
        assertThat(response.scores()).isFalse();
    }

    @Test
    void refusesASecondRecordOnTheSameCase() {
        when(repository.findByCaseId(77L)).thenReturn(Optional.of(new InsuredFraudRecord()));

        assertThatThrownBy(() -> service.register(request(FraudRecordSource.EXPERT_BACKED, 5L)))
                .isInstanceOf(FraudRecordAlreadyExistsException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void refusesExpertBackingWithNoAssessmentBehindIt() {
        when(repository.findByCaseId(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(request(FraudRecordSource.EXPERT_BACKED, null)))
                .isInstanceOf(UnsupportedFraudRecordException.class);
        verify(repository, never()).save(any());
    }

    /**
     * A lapsed record still comes back — "hubo un antecedente y ya no cuenta" and "no hubo ninguno"
     * are different answers for the analyst.
     */
    @Test
    void lapsedRecordsAreReturnedFlaggedAsOutOfForce() {
        InsuredFraudRecord old = record(FraudRecordSource.EXPERT_BACKED, null);
        old.setDeclaredAt(Instant.now().minus(31L * 61, ChronoUnit.DAYS));
        when(repository.findByInsuredDniOrderByDeclaredAtDesc(DNI)).thenReturn(List.of(old));

        assertThat(service.findByInsured(DNI)).singleElement()
                .satisfies(response -> {
                    assertThat(response.inForce()).isFalse();
                    assertThat(response.scores()).isFalse();
                });
    }

    /** The insurer with the rule off sees its records; none of them count. */
    @Test
    void withThePolicyOffNothingScores() {
        when(rulesAdapter.getFraudRecordPolicy()).thenReturn(BusinessRules.FraudRecordPolicy.disabled());
        InsuredFraudRecord recent = record(FraudRecordSource.EXPERT_BACKED, 5L);
        recent.setDeclaredAt(Instant.now());
        when(repository.findByInsuredDniOrderByDeclaredAtDesc(DNI)).thenReturn(List.of(recent));

        assertThat(service.findByInsured(DNI)).singleElement()
                .satisfies(response -> {
                    assertThat(response.inForce()).isTrue();
                    assertThat(response.scores()).isFalse();
                });
    }

    private FraudRecordRequest request(FraudRecordSource source, Long expertAssessmentId) {
        return new FraudRecordRequest(DNI, 77L, source,
                "El peritaje verificó que el equipo denunciado nunca existió",
                expertAssessmentId, 1L, "Ana Gómez");
    }

    private InsuredFraudRecord record(FraudRecordSource source, Long expertAssessmentId) {
        return InsuredFraudRecord.builder()
                .id(1L)
                .insuredDni(DNI)
                .caseId(77L)
                .source(source)
                .reason("El peritaje verificó que el equipo denunciado nunca existió")
                .expertAssessmentId(expertAssessmentId)
                .declaredByAnalystId(1L)
                .declaredByAnalystName("Ana Gómez")
                .build();
    }

    private BusinessRules.FraudRecordPolicy activePolicy() {
        return BusinessRules.FraudRecordPolicy.builder()
                .ruleId(17L).enabled(true).windowMonths(60).blocksFastTrack(true).build();
    }
}
