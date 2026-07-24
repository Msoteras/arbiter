package ar.edu.utn.frba.arbiter.auth.config;

import com.auth0.client.auth.AuthAPI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "arbiter.auth", name = "provider", havingValue = "auth0")
public class Auth0Config {

    @Bean
    public AuthAPI auth0AuthApi(AuthProperties properties) {
        AuthProperties.Auth0 auth0 = properties.auth0();
        return AuthAPI.newBuilder(auth0.domain(), auth0.clientId(), auth0.clientSecret()).build();
    }

    /** Cliente M2M usado solo para pedir tokens de la Management API (alta de usuarios). */
    @Bean
    public AuthAPI auth0ManagementAuthApi(AuthProperties properties) {
        AuthProperties.Auth0 auth0 = properties.auth0();
        AuthProperties.ManagementApi managementApi = properties.managementApi();
        return AuthAPI.newBuilder(auth0.domain(), managementApi.clientId(), managementApi.clientSecret()).build();
    }
}
