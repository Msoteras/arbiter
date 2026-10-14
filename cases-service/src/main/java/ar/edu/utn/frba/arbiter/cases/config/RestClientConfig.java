package ar.edu.utn.frba.arbiter.cases.config;

import ar.edu.utn.frba.arbiter.common.http.ConnectionRetryInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    public ConnectionRetryInterceptor connectionRetry() {
        return ConnectionRetryInterceptor.forBootingModule();
    }

    @Bean
    public RestClient.Builder restClientBuilder(ConnectionRetryInterceptor connectionRetry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(connectionRetry);
    }
}
