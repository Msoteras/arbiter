package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.rules.dto.FactorWeightDto;
import ar.edu.utn.frba.arbiter.rules.dto.ScoringConfigDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.FactorWeight;
import ar.edu.utn.frba.arbiter.rules.models.entities.ScoringConfiguration;
import ar.edu.utn.frba.arbiter.rules.models.repositories.FactorWeightRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ScoreBandRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ScoringConfigurationHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ScoringConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScoringConfigurationServiceTest {

    private static final String ACTOR = "referente@aseguradora.com";

    private final ScoringConfigurationRepository configRepository = mock(ScoringConfigurationRepository.class);
    private final FactorWeightRepository factorRepository = mock(FactorWeightRepository.class);
    private final ScoreBandRepository bandRepository = mock(ScoreBandRepository.class);
    private final ScoringConfigurationHistoryRepository historyRepository =
            mock(ScoringConfigurationHistoryRepository.class);
    private final OnboardingTracker tracker = mock(OnboardingTracker.class);
    private final ScoringConfigurationService service = new ScoringConfigurationService(
            configRepository, factorRepository, bandRepository, historyRepository,
            mock(RuleAuthorResolver.class), tracker);

    @Test
    void savesWeightsThatAddUpToOneHundredPercent() {
        givenExistingConfig();

        service.upsert(config(factor("amount_ratio", "0.60"), factor("claim_frequency", "0.40")), ACTOR);

        ArgumentCaptor<FactorWeight> saved = ArgumentCaptor.forClass(FactorWeight.class);
        verify(factorRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(FactorWeight::getWeight)
                .containsExactly(new BigDecimal("0.60"), new BigDecimal("0.40"));
    }

    @Test
    void acceptsTheSameTotalWrittenWithAnotherScale() {
        givenExistingConfig();

        service.upsert(config(factor("amount_ratio", "0.6000"), factor("claim_frequency", "0.4")), ACTOR);

        verify(factorRepository, times(2)).save(any(FactorWeight.class));
    }

    @Test
    void savesAConfigWithoutFactorsAsScoringOff() {
        givenExistingConfig();

        service.upsert(config(), ACTOR);

        verify(factorRepository, never()).save(any(FactorWeight.class));
        verify(bandRepository).deleteByScoringConfiguration_Id(1L);
    }

    @Test
    void rejectsWeightsThatAddUpToLessThanOneHundredPercent() {
        assertRejected(config(factor("amount_ratio", "0.60"), factor("claim_frequency", "0.25")),
                "Los pesos suman 85% y tienen que sumar 100%.");
    }

    @Test
    void rejectsTheRelativeWeightsSavedBeforeTheRule() {
        assertRejected(config(factor("amount_ratio", "1.00"), factor("claim_frequency", "0.30")),
                "Los pesos suman 130% y tienen que sumar 100%.");
    }

    @Test
    void rejectsAnActiveFactorWithoutWeight() {
        assertRejected(config(factor("amount_ratio", "1.00"), factor("claim_frequency", "0")),
                "Cada factor prendido necesita un peso mayor que 0%.");
    }

    @Test
    void rejectsAnActiveFactorWithANullWeight() {
        assertRejected(config(factor("amount_ratio", "1.00"), factor("claim_frequency", null)),
                "Cada factor prendido necesita un peso mayor que 0%.");
    }

    @Test
    void rejectsFractionsOfAPercent() {
        assertRejected(config(factor("amount_ratio", "0.155"), factor("claim_frequency", "0.845")),
                "Los pesos van en porcentajes enteros.");
    }

    private void assertRejected(ScoringConfigDto dto, String detail) {
        assertThatThrownBy(() -> service.upsert(dto, ACTOR))
                .isInstanceOf(InvalidRuleConfigurationException.class)
                .hasMessage(detail);
        verifyNoInteractions(configRepository, factorRepository, bandRepository, historyRepository, tracker);
    }

    private void givenExistingConfig() {
        ScoringConfiguration existing = ScoringConfiguration.builder().id(1L).name("Scoring de la aseguradora").build();
        when(configRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(existing));
    }

    private static ScoringConfigDto config(FactorWeightDto... factors) {
        return new ScoringConfigDto(null, false, List.of(factors), List.of());
    }

    private static FactorWeightDto factor(String id, String weight) {
        return new FactorWeightDto(id, weight == null ? null : new BigDecimal(weight));
    }
}
