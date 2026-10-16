package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;
import ar.edu.utn.frba.arbiter.rules.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.rules.support.AbstractPersistenceIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=" + OnboardingTrackerTests.POOL_SIZE,
        "spring.datasource.hikari.connection-timeout=3000"
})
class OnboardingTrackerTests extends AbstractPersistenceIT {

    static final int POOL_SIZE = 2;

    private static final int CONCURRENT_SAVES = 6;
    private static final String TENANT = "arbiter_it_tracker";
    private static final String COVERAGES_KEY = OnboardingStep.COVERAGES.key(1L);

    @Autowired
    private OnboardingTracker tracker;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void freshTenant() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + TENANT + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + TENANT);
        jdbc.execute("""
                CREATE TABLE %s.onboarding_step (
                    step_key    VARCHAR(40) PRIMARY KEY,
                    skipped     BOOLEAN     NOT NULL DEFAULT FALSE,
                    touched_at  TIMESTAMPTZ
                )""".formatted(TENANT));
        jdbc.execute("CREATE TABLE %s.saved_section (id INT PRIMARY KEY)".formatted(TENANT));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void moreConcurrentSavesThanPooledConnectionsAllFinishAndRecordTheStep() throws Exception {
        CyclicBarrier wholePoolTaken = new CyclicBarrier(POOL_SIZE);
        List<Future<?>> saves = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_SAVES)) {
            for (int section = 1; section <= CONCURRENT_SAVES; section++) {
                int id = section;
                saves.add(executor.submit(() -> {
                    TenantContext.set(TENANT);
                    try {
                        save(id, () -> await(wholePoolTaken));
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            for (Future<?> save : saves) {
                save.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(savedSections()).isEqualTo(CONCURRENT_SAVES);
        assertThat(isTouched(COVERAGES_KEY)).isTrue();
    }

    @Test
    void aSaveThatRollsBackDoesNotRecordTheStep() {
        TenantContext.set(TENANT);

        assertThatThrownBy(() -> save(1, () -> {
            throw new IllegalStateException("save failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(savedSections()).isZero();
        assertThat(isTouched(COVERAGES_KEY)).isFalse();
    }

    @Test
    void aFailedRecordingDoesNotUndoTheSave() {
        jdbc.execute("ALTER TABLE %s.onboarding_step ADD CONSTRAINT rejects_touches CHECK (touched_at IS NULL)"
                .formatted(TENANT));
        TenantContext.set(TENANT);

        save(1, () -> { });

        assertThat(savedSections()).isEqualTo(1);
        assertThat(isTouched(COVERAGES_KEY)).isFalse();
    }

    @Test
    void outsideATransactionTheStepIsRecordedInTheTenantAndKeepsItsSkip() {
        jdbc.update("INSERT INTO %s.onboarding_step (step_key, skipped) VALUES (?, true)".formatted(TENANT), "FRAUD");
        TenantContext.set(TENANT);

        tracker.touch(OnboardingStep.FRAUD, null);

        assertThat(isTouched("FRAUD")).isTrue();
        assertThat(jdbc.queryForObject("SELECT skipped FROM %s.onboarding_step WHERE step_key = ?".formatted(TENANT),
                Boolean.class, "FRAUD")).isTrue();
    }

    private void save(int sectionId, Runnable beforeCommit) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO saved_section (id) VALUES (?)", sectionId);
            tracker.touch(OnboardingStep.COVERAGES, 1L);
            beforeCommit.run();
        });
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int savedSections() {
        return jdbc.queryForObject("SELECT count(*) FROM %s.saved_section".formatted(TENANT), Integer.class);
    }

    private boolean isTouched(String key) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM %s.onboarding_step WHERE step_key = ? AND touched_at IS NOT NULL".formatted(TENANT),
                Integer.class, key) == 1;
    }
}
