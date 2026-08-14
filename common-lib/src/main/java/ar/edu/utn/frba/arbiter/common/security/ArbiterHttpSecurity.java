package ar.edu.utn.frba.arbiter.common.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Wiring common to every module: no server session and no CSRF (architecture decision #13), no
 * anonymous authentication (so that "no token" returns 401 and not 403 — by default Spring Security
 * treats the anonymous user as "authenticated" and a failed {@code isAuthenticated()} ends in 403),
 * and the JWT filter before the user/password one. {@code /error} stays public: when Spring
 * Security denies (401/403), the container does an internal forward to {@code /error} to render it,
 * and that forward goes through the security filter again — without this permitAll, that second
 * request (unable to re-authenticate like the original) overwrites the real status code. Each
 * module adds its own per-endpoint authorization rules on top of this.
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
