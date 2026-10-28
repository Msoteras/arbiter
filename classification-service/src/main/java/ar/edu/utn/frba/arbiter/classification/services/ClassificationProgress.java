package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.CasesServiceStepPublisher;
import ar.edu.utn.frba.arbiter.common.dto.ClassificationStep;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Hands each step of a running classification to the publisher, off the classification thread.
 * The case being classified is bound to the thread for the duration of the run, so the
 * orchestrator reports steps without threading a case id through every private method; a run
 * without a case (the isolated classification) reports nothing.
 */
@Component
public class ClassificationProgress {

    private static final ThreadLocal<Long> CURRENT_CASE = new ThreadLocal<>();

    private final CasesServiceStepPublisher publisher;
    private final Executor executor;

    public ClassificationProgress(CasesServiceStepPublisher publisher,
                                  @Qualifier("classificationStepExecutor") Executor executor) {
        this.publisher = publisher;
        this.executor = executor;
    }

    public void bind(Long caseId) {
        CURRENT_CASE.set(caseId);
    }

    public void unbind() {
        CURRENT_CASE.remove();
    }

    /** For the run bound to this thread. */
    public void report(ClassificationStep step) {
        Long caseId = CURRENT_CASE.get();
        if (caseId != null) {
            report(caseId, step);
        }
    }

    public void report(Long caseId, ClassificationStep step) {
        executor.execute(() -> publisher.publish(caseId, step));
    }
}
