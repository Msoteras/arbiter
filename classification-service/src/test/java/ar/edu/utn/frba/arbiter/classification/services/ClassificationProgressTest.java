package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.CasesServiceStepPublisher;
import ar.edu.utn.frba.arbiter.common.dto.ClassificationStep;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ClassificationProgressTest {

    @Mock
    private CasesServiceStepPublisher publisher;

    /** Runs the task in place, so the test sees what the step thread would send. */
    private ClassificationProgress progress() {
        return new ClassificationProgress(publisher, Runnable::run);
    }

    @AfterEach
    void unbind() {
        progress().unbind();
    }

    @Test
    void aStepOfTheBoundCase_isPublished() {
        ClassificationProgress progress = progress();
        ClassificationStep step = ClassificationStep.of(ClassificationStage.POLICY);

        progress.bind(7L);
        progress.report(step);

        verify(publisher).publish(7L, step);
    }

    /** The isolated classification runs without a case: nothing to show on the board. */
    @Test
    void withoutABoundCase_nothingIsPublished() {
        progress().report(ClassificationStep.of(ClassificationStage.POLICY));

        verify(publisher, never()).publish(anyLong(), any());
    }

    @Test
    void afterUnbinding_nothingIsPublished() {
        ClassificationProgress progress = progress();
        progress.bind(7L);
        progress.unbind();

        progress.report(ClassificationStep.of(ClassificationStage.MODEL));

        verify(publisher, never()).publish(anyLong(), any());
    }
}
