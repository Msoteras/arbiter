package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.rules.dto.FactorWeightDto;
import ar.edu.utn.frba.arbiter.rules.dto.ScoreBandCutDto;
import ar.edu.utn.frba.arbiter.rules.dto.ScoringConfigDto;
import ar.edu.utn.frba.arbiter.rules.dto.ScoringConfigResponse;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.FactorWeight;
import ar.edu.utn.frba.arbiter.rules.models.entities.ScoreBand;
import ar.edu.utn.frba.arbiter.rules.models.entities.ScoringConfiguration;
import ar.edu.utn.frba.arbiter.rules.models.entities.ScoringConfigurationHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.FactorWeightRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ScoreBandRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ScoringConfigurationHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ScoringConfigurationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The insurer's fraud scoring (factors + bands), for the referente's Scoring tab. A single row per
 * tenant: every branch shares the same scoring. Each save snapshots the previous version into
 * {@code scoring_configuration_history} (append-only, same pattern as
 * {@link FastTrackRuleService}).
 */
@Service
@RequiredArgsConstructor
public class ScoringConfigurationService {

    // Self-instantiated (Jackson 2): ver el comentario equivalente en FastTrackRuleService.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ScoringConfigurationRepository scoringConfigurationRepository;
    private final FactorWeightRepository factorWeightRepository;
    private final ScoreBandRepository scoreBandRepository;
    private final ScoringConfigurationHistoryRepository historyRepository;

    @Transactional(readOnly = true)
    public ScoringConfigDto get() {
        return scoringConfigurationRepository.findFirstByOrderByIdAsc()
                .map(this::toDto)
                .orElseGet(ScoringConfigDto::empty);
    }

    @Transactional
    public ScoringConfigResponse upsert(ScoringConfigDto dto, String actorEmail) {
        ScoringConfiguration config = scoringConfigurationRepository.findFirstByOrderByIdAsc().orElse(null);
        Instant now = Instant.now();

        if (config == null) {
            config = ScoringConfiguration.builder()
                    .name("Scoring de la aseguradora")
                    .active(dto.enabled())
                    .fullAnalysisOnFastTrack(dto.fullAnalysisOnFastTrack())
                    .validFrom(now)
                    .build();
            config = scoringConfigurationRepository.save(config);
            saveChildren(config, dto);
            return new ScoringConfigResponse(config.getId(), toDto(config));
        }

        historyRepository.save(ScoringConfigurationHistory.builder()
                .snapshotConfig(serialize(toDto(config)))
                .validFrom(config.getValidFrom())
                .validTo(now)
                .changedAt(now)
                .reason("Scoring actualizado por " + actorEmail)
                .scoringConfiguration(config)
                .changedBy(null)
                .build());

        factorWeightRepository.deleteByScoringConfiguration_Id(config.getId());
        scoreBandRepository.deleteByScoringConfiguration_Id(config.getId());
        config.setActive(dto.enabled());
        config.setFullAnalysisOnFastTrack(dto.fullAnalysisOnFastTrack());
        config.setValidFrom(now);
        scoringConfigurationRepository.save(config);
        saveChildren(config, dto);
        return new ScoringConfigResponse(config.getId(), toDto(config));
    }

    private void saveChildren(ScoringConfiguration config, ScoringConfigDto dto) {
        for (FactorWeightDto f : dto.factors()) {
            factorWeightRepository.save(FactorWeight.builder()
                    .factorCode(f.factorId())
                    .weight(f.weight())
                    .scoringConfiguration(config)
                    .build());
        }
        for (ScoreBandCutDto b : dto.bands()) {
            scoreBandRepository.save(ScoreBand.builder()
                    .band(b.band())
                    .minScoreInclusive(b.minScoreInclusive())
                    .active(true)
                    .scoringConfiguration(config)
                    .build());
        }
    }

    private ScoringConfigDto toDto(ScoringConfiguration config) {
        return new ScoringConfigDto(
                config.getId(),
                config.isActive(),
                config.isFullAnalysisOnFastTrack(),
                factorWeightRepository.findByScoringConfiguration_Id(config.getId()).stream()
                        .map(f -> new FactorWeightDto(f.getFactorCode(), f.getWeight()))
                        .toList(),
                scoreBandRepository.findByScoringConfiguration_Id(config.getId()).stream()
                        .map(b -> new ScoreBandCutDto(b.getBand(), b.getMinScoreInclusive()))
                        .toList());
    }

    private String serialize(ScoringConfigDto dto) {
        try {
            return OBJECT_MAPPER.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException("No se pudo serializar la configuración de scoring");
        }
    }
}
