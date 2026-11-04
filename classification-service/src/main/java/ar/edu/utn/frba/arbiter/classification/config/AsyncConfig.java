package ar.edu.utn.frba.arbiter.classification.config;

import ar.edu.utn.frba.arbiter.classification.config.tenant.TenantAwareTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "classificationExecutor")
    public Executor classificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("classification-async-");
        // Without it the async run would use arbiter_common instead of the insurer's schema.
        executor.setTaskDecorator(new TenantAwareTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * One thread keeps a case's steps in order. Bounded and discarding: if cases-service stops
     * answering, steps are dropped instead of piling up in memory or slowing the classification.
     */
    @Bean(name = "classificationStepExecutor")
    public Executor classificationStepExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("classification-step-");
        executor.setTaskDecorator(new TenantAwareTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
