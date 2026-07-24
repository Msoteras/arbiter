package ar.edu.utn.frba.arbiter.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.List;

/**
 * Valida el JWT propio (H0001, transitorio hasta Auth0) y puebla el SecurityContext con el rol
 * como authority (ROLE_&lt;rol&gt;). Un token ausente o inválido deja la request sin autenticar —
 * cada servicio decide con su propio SecurityConfig si eso alcanza (endpoint público) o no.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final SecretKey key;

    public JwtAuthenticationFilter(SecretKey key) {
        this.key = key;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = Jwts.parser().verifyWith(key).build()
                        .parseSignedClaims(header.substring(7))
                        .getPayload();

                String rol = claims.get("rol", String.class);
                List<GrantedAuthority> authorities = rol != null
                        ? List.of(new SimpleGrantedAuthority("ROLE_" + rol))
                        : List.of();

                var authentication = new UsernamePasswordAuthenticationToken(
                        claims.getSubject(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ex) {
                log.warn("JWT inválido o expirado en {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}
