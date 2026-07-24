package ar.edu.utn.frba.arbiter.cases.config;

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
 * Valida el mismo JWT que emite auth-service (H0001) — requiere el mismo JWT_SECRET.
 * RBAC por endpoint vía {@code @PreAuthorize} en {@link ar.edu.utn.frba.arbiter.cases.controllers.CaseController}
 * (H0003). Sin sesión de servidor: el estado vive en el JWT (decisión de arquitectura #13).
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
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        ArbiterHttpSecurity.configure(http, jwtFilter);
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }
}
