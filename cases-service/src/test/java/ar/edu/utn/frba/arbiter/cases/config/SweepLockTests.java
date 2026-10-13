package ar.edu.utn.frba.arbiter.cases.config;

import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.services.DeadlineSweepScheduler;
import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A sweep another instance is running is skipped, not run alongside it. The other instance is
 * played by taking the sweep's lock by hand.
 *
 * <p>With no insurers no sweep gets past its first line, so the clock is only ever read by the
 * deadline sweep: reading it is the evidence that the sweep ran.
 */
@SpringBootTest
class SweepLockTests extends AbstractPersistenceIT {

    @MockitoBean private Clock clock;
    @MockitoBean private InsurerRepository insurerRepository;

    @Autowired private DeadlineSweepScheduler scheduler;
    @Autowired private LockProvider lockProvider;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(Instant.parse("2026-08-24T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(insurerRepository.findByActiveTrue()).thenReturn(List.of());
    }

    @Test
    void aSweepHeldByAnotherInstance_isSkipped_andRunsOnceReleased() {
        SimpleLock heldElsewhere = lockProvider.lock(new LockConfiguration(
                Instant.now(), "deadline-sweep", Duration.ofMinutes(1), Duration.ZERO)).orElseThrow();

        scheduler.sweepDeadlines();
        verify(clock, never()).getZone();

        heldElsewhere.unlock();
        scheduler.sweepDeadlines();
        verify(clock, atLeastOnce()).getZone();
    }

    @Test
    void theLockIsReleasedWhenTheSweepEnds() {
        scheduler.sweepDeadlines();

        assertThat(lockProvider.lock(new LockConfiguration(
                Instant.now(), "deadline-sweep", Duration.ofMinutes(1), Duration.ZERO)))
                .hasValueSatisfying(SimpleLock::unlock);
    }
}
