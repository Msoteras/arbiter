package ar.edu.utn.frba.arbiter.cases.config;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.services.CaseAccessPolicy;
import ar.edu.utn.frba.arbiter.cases.services.CaseTopic;
import ar.edu.utn.frba.arbiter.cases.services.InsurerTenantScope;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.Map;

/**
 * Authenticates the STOMP session and decides who may subscribe to a case's conversation.
 *
 * <p>Neither servlet filter runs here: after the handshake every frame travels outside the filter
 * chain, so identity and tenant are re-established on this channel. The token comes in the CONNECT
 * frame, not the URL — a query string would land in nginx's and the platform's access logs.
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER = "Bearer ";
    private static final String CALLER = "arbiter.caller";
    private static final String TENANT = "arbiter.tenant";
    private static final String PRINCIPAL = "arbiter.principal";
    private static final String AUTHORITIES = "arbiter.authorities";

    private final SecretKey jwtKey;
    private final CaseRepository caseRepository;
    private final CaseAccessPolicy accessPolicy;
    private final InsurerTenantScope tenantScope;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        if (accessor.getCommand() == StompCommand.CONNECT) {
            authenticate(accessor);
        } else if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            authorizeSubscription(accessor);
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER)) {
            throw new StompAccessDeniedException("Falta el token en la conexión.");
        }
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(jwtKey).build()
                    .parseSignedClaims(header.substring(BEARER.length()))
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new StompAccessDeniedException("Token inválido o expirado.");
        }

        String rol = claims.get("rol", String.class);
        var authentication = new UsernamePasswordAuthenticationToken(
                claims.getSubject(), null, authorities(rol));
        accessor.setUser(authentication);

        Map<String, Object> session = accessor.getSessionAttributes();
        if (session != null) {
            session.put(PRINCIPAL, claims.getSubject());
            session.put(AUTHORITIES, rol);
            session.put(TENANT, claims.get("tenantSchema", String.class));
            session.put(CALLER, new CallerContext.Caller(
                    claims.get("insuredId", String.class),
                    insurerIds(claims),
                    claims.get("tenantSchema", String.class)));
        }
    }

    /** Without this, any authenticated session could walk the case ids and read every thread. */
    private void authorizeSubscription(StompHeaderAccessor accessor) {
        CaseTopic.Ref ref = CaseTopic.parse(accessor.getDestination())
                .orElseThrow(() -> new StompAccessDeniedException("Destino no permitido."));

        Map<String, Object> session = accessor.getSessionAttributes();
        if (session == null || session.get(PRINCIPAL) == null) {
            throw new StompAccessDeniedException("Sesión sin identificar.");
        }

        restore(session);
        try {
            tenantScope.forCase(ref.caseId(), ref.insurerSlug(), () -> {
                accessPolicy.assertCanRead(caseRepository.findById(ref.caseId())
                        .orElseThrow(() -> new StompAccessDeniedException("Expediente inexistente.")));
                return null;
            });
        } catch (StompAccessDeniedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new StompAccessDeniedException("No podés seguir esta conversación.");
        } finally {
            clear();
        }
    }

    private void restore(Map<String, Object> session) {
        String rol = (String) session.get(AUTHORITIES);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                session.get(PRINCIPAL), null, authorities(rol)));
        TenantContext.set((String) session.get(TENANT));
        CallerContext.set((CallerContext.Caller) session.get(CALLER));
    }

    private void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CallerContext.clear();
    }

    private static List<GrantedAuthority> authorities(String rol) {
        return rol != null ? List.of(new SimpleGrantedAuthority("ROLE_" + rol)) : List.of();
    }

    private static List<Long> insurerIds(Claims claims) {
        Object raw = claims.get("insurerIds");
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(Number.class::isInstance)
                .map(value -> ((Number) value).longValue())
                .toList();
    }
}
