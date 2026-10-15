package ar.edu.utn.frba.arbiter.cases.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import javax.sql.DataSource;

/**
 * Every sweep is {@code @SchedulerLock}ed: with several instances each one fires its own timers,
 * and two of them running the same sweep at once race on its check-then-write steps (the same
 * deadline notice mailed twice, the same policy coverage inserted twice). Runs one after another
 * were already safe, so the lock is released as soon as a sweep ends.
 *
 * <p>The lock is a row in {@code arbiter_common.shedlock}, not held on a connection: an idle
 * instance keeps its pool empty. {@code lockAtMostFor} only matters when an instance dies
 * mid-sweep, as the time after which another may take over.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulingConfig implements SchedulingConfigurer {

    private static final int POOL_SIZE = 4;

    @Bean
    public ThreadPoolTaskScheduler sweepTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("sweep-");
        return scheduler;
    }

    /** Schema-qualified: the lock is taken before any tenant is resolved, on a plain connection. */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("arbiter_common.shedlock")
                // The database's clock, so instances with drifting clocks agree on when a lock expires.
                .usingDbTime()
                .build());
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(sweepTaskScheduler());
    }
}
