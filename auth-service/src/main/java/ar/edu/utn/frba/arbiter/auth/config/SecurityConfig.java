package ar.edu.utn.frba.arbiter.auth.config;

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
 * Login queda público (es el único punto de entrada sin JWT); todo lo demás requiere estar
 * autenticado. Sin sesión de servidor (decisión de arquitectura #13): el estado vive en el JWT.
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
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        ArbiterHttpSecurity.configure(http, jwtFilter);
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/login", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }
}
