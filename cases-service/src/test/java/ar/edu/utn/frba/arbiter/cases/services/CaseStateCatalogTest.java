package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.exceptions.UnknownCaseStateException;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseStateRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaseStateCatalogTest {

    @Mock
    private CaseStateRepository caseStateRepository;

    @InjectMocks
    private CaseStateCatalog caseStateCatalog;

    @Test
    void resolve_returnsTheRowMatchingTheEnumLiteral() {
        CaseState row = CaseStates.of(CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseStateRepository.findByName("PENDING_ANALYST_REVIEW")).thenReturn(Optional.of(row));

        assertThat(caseStateCatalog.resolve(CaseStatus.PENDING_ANALYST_REVIEW)).isSameAs(row);
    }

    @Test
    void resolve_hitsTheDatabaseOnlyOncePerStatus() {
        // El catálogo se lee en cada transición y en cada alta; sin cache sería un SELECT extra
        // por expediente creado.
        when(caseStateRepository.findByName("APPROVED"))
                .thenReturn(Optional.of(CaseStates.of(CaseStatus.APPROVED)));

        caseStateCatalog.resolve(CaseStatus.APPROVED);
        caseStateCatalog.resolve(CaseStatus.APPROVED);
        caseStateCatalog.resolve(CaseStatus.APPROVED);

        verify(caseStateRepository, times(1)).findByName("APPROVED");
    }

    @Test
    void resolve_statusMissingFromTheCatalog_throws() {
        when(caseStateRepository.findByName("REJECTED")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> caseStateCatalog.resolve(CaseStatus.REJECTED))
                .isInstanceOf(UnknownCaseStateException.class)
                .hasMessageContaining("REJECTED");
    }
}
