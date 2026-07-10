package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.classification.models.entities.ClassificationLog;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ClassificationLogRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ClaimRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaimServiceTest {

    @Mock
    private ClaimRepository claimRepository;

    @Mock
    private ClassificationLogRepository logRepository;

    @InjectMocks
    private ClaimService claimService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void recordAnalystDecisionPersistsDecisionAndTimestamp() {
        Long claimId = 42L;
        when(claimRepository.existsById(claimId)).thenReturn(true);

        ClassificationLog log = new ClassificationLog();
        log.setClaimId(claimId);
        when(logRepository.findFirstByClaimIdOrderByIdDesc(claimId)).thenReturn(Optional.of(log));

        AnalystDecisionRequest request = new AnalystDecisionRequest("analyst-1", "APROBAR");

        claimService.recordAnalystDecision(claimId, request);

        assertEquals("analyst-1", log.getAnalistaId());
        assertEquals("APPROVE", log.getDecision());
        assertNotNull(log.getDecisionTimestamp());
        verify(logRepository).save(log);
    }
}
