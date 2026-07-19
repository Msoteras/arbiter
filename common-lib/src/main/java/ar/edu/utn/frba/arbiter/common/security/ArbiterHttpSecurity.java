package ar.edu.utn.frba.arbiter.common.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Wiring común a todos los módulos: sin sesión de servidor ni CSRF (decisión de arquitectura
 * #13), sin autenticación anónima (para que "sin token" devuelva 401 y no 403 — por default,
 * Spring Security trata al anónimo como "autenticado" y un {@code isAuthenticated()} fallido
 * termina en 403), y el filtro de JWT antes del de user/password. {@code /error} queda público:
 * cuando Spring Security deniega (401/403), el contenedor hace un forward interno a {@code /error}
 * para renderizarlo, y ese forward vuelve a pasar por el filtro de seguridad — sin este permitAll,
 * ese segundo request (sin poder reautenticar igual que el original) pisa el status code real.
 * Cada módulo agrega sus propias reglas de autorización por endpoint encima de esto.
 */
public final class ArbiterHttpSecurity {

    private ArbiterHttpSecurity() {}

    public static void configure(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/error").permitAll())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        (request, response, authException) -> response.sendError(HttpStatus.UNAUTHORIZED.value())))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
    }
}
