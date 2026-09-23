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
                        "/api/v1/auth/public-key",
                        "/api/v1/auth/activate",
                        "/api/v1/auth/forgot-password",
                        "/api/v1/auth/reset-password",
                        "/api/v1/auth/invite-tokens/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        // Liveness probe has no JWT; only `health` is exposed by actuator.
                        "/actuator/health",
                        "/actuator/health/**")
                .permitAll()
                .anyRequest().authenticated());
        return http.build();
    }
}
