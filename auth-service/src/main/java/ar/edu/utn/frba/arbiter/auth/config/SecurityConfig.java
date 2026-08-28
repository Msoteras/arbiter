package ar.edu.utn.frba.arbiter.auth.config;

import ar.edu.utn.frba.arbiter.auth.config.tenant.TenantResolvingFilter;
import ar.edu.utn.frba.arbiter.auth.services.JwtService;
import ar.edu.utn.frba.arbiter.common.security.ArbiterHttpSecurity;
import ar.edu.utn.frba.arbiter.common.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Login stays public (it's the only entry point without a JWT); everything else needs to be
 * authenticated. No server session (architecture decision #13): the state lives in the JWT.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService jwtService;

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtService.getKey());
    }

    @Bean
    public TenantResolvingFilter tenantResolvingFilter() {
        return new TenantResolvingFilter(jwtService.getKey());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtAuthenticationFilter jwtFilter, TenantResolvingFilter tenantFilter) throws Exception {
        ArbiterHttpSecurity.configure(http, jwtFilter);
        http.addFilterAfter(tenantFilter, JwtAuthenticationFilter.class);
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/api/v1/auth/login",
                        // Public by necessity: it's the first thing the browser asks for, before it
                        // has anything to authenticate with. It's the public key, nothing to guard.
                        "/api/v1/auth/public-key",
                        "/api/v1/auth/activate",
                        "/api/v1/auth/forgot-password",
                        "/api/v1/auth/reset-password",
                        "/api/v1/auth/invite-tokens/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        // The platform's liveness probe has no JWT to present. Only `health` is
                        // exposed (see application.yml), so this opens a status word, nothing else.
                        "/actuator/health",
                        "/actuator/health/**")
                .permitAll()
                .anyRequest().authenticated());
        return http.build();
    }
}
