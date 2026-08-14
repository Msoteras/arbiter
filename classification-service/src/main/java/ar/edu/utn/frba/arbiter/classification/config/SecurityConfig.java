package ar.edu.utn.frba.arbiter.classification.config;

import ar.edu.utn.frba.arbiter.classification.config.tenant.TenantResolvingFilter;
import ar.edu.utn.frba.arbiter.common.security.ArbiterHttpSecurity;
import ar.edu.utn.frba.arbiter.common.security.JwtAuthenticationFilter;
import ar.edu.utn.frba.arbiter.common.security.JwtSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Validates the same JWT auth-service issues (H0001) — needs the same JWT_SECRET.
 * RBAC por endpoint vía {@code @PreAuthorize} en {@link ar.edu.utn.frba.arbiter.classification.controllers.ClaimController}
 * y {@link ar.edu.utn.frba.arbiter.classification.controllers.ClassificationController} (H0003).
 * cases-service forwards the original user's JWT on calls made inside a request (claim creation,
 * analyst decision); the background poller signs its own service token with the same secret (see
 * {@code ClassificationServiceClient}). No server session: the state lives in the JWT (architecture
 * decision #13).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${arbiter.auth.jwt.secret}")
    private String jwtSecret;

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(JwtSupport.key(jwtSecret));
    }

    @Bean
    public TenantResolvingFilter tenantResolvingFilter() {
        return new TenantResolvingFilter(JwtSupport.key(jwtSecret));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtAuthenticationFilter jwtFilter, TenantResolvingFilter tenantFilter) throws Exception {
        ArbiterHttpSecurity.configure(http, jwtFilter);
        // After authentication, so an invalid token never resolves a tenant.
        http.addFilterAfter(tenantFilter, JwtAuthenticationFilter.class);
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }
}
