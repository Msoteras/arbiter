package ar.edu.utn.frba.arbiter.siniestros.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "clasificacionExecutor")
    public Executor clasificacionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);           // mínimo 5 threads (virtual threads)
        executor.setMaxPoolSize(10);           // máximo 10 threads
        executor.setQueueCapacity(50);         // cola de 50 tareas pendientes
        executor.setThreadNamePrefix("clasificacion-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
