package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidLogoException;
import ar.edu.utn.frba.arbiter.auth.exceptions.LogoNotFoundException;
import ar.edu.utn.frba.arbiter.auth.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsurerLogoRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.common.models.entities.InsurerLogo;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The logo of the insurer the request is for: the one whose schema the JWT resolved to. Every user
 * of the insurer reads it; only the referent changes it (enforced by the controller).
 */
@Service
@RequiredArgsConstructor
public class InsurerLogoService {

    /** A logo is a small mark: anything bigger is a photo or an unoptimized export. */
    private static final String SCHEMA_PREFIX = "arbiter_";

    public static final int MAX_BYTES = 256 * 1024;

    /** The declared type has to match the file's first bytes: the header alone is the client's word. */
    private static final Map<String, byte[]> SIGNATURES = Map.of(
            "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'},
            "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

    private final InsurerLogoRepository repository;
    private final InsurerRepository insurers;
    private final UserRepository users;
    private final TenantResolver tenantResolver;

    @Transactional(readOnly = true)
    public InsurerLogo get() {
        return repository.findById(currentInsurerId()).orElseThrow(LogoNotFoundException::new);
    }

    /**
     * The logo of one of the user's insurers, by the slug the portal uses for it: each claim shows
     * the insurer it belongs to. Not found for an insurer the user doesn't belong to.
     */
    @Transactional(readOnly = true)
    public InsurerLogo forInsurer(String insurerSlug, String email) {
        Long userId = users.findByEmail(email)
                .map(User::getId)
                .orElseThrow(LogoNotFoundException::new);
        Long insurerId = insurers.findAllById(tenantResolver.insurerIdsFor(userId)).stream()
                .filter(insurer -> slugOf(insurer).equalsIgnoreCase(insurerSlug))
                .map(Insurer::getId)
                .findFirst()
                .orElseThrow(LogoNotFoundException::new);
        return repository.findById(insurerId).orElseThrow(LogoNotFoundException::new);
    }

    /**
     * Public: the mails to the insured point their <img> here. Only PNG and JPG, the formats the
     * mail clients show; an older WebP or SVG upload reads as no logo.
     */
    @Transactional(readOnly = true)
    public InsurerLogo publicLogo(String insurerSlug) {
        return insurers.findAll().stream()
                .filter(insurer -> slugOf(insurer).equalsIgnoreCase(insurerSlug))
                .findFirst()
                .flatMap(insurer -> repository.findById(insurer.getId()))
                .filter(logo -> SIGNATURES.containsKey(logo.getContentType()))
                .orElseThrow(LogoNotFoundException::new);
    }

    /** The schema without its prefix, the same rule as cases-service's InsurerSlug: "arbiter_bbva" → "bbva". */
    private static String slugOf(Insurer insurer) {
        String schema = insurer.getSchemaName();
        return schema.startsWith(SCHEMA_PREFIX) ? schema.substring(SCHEMA_PREFIX.length()) : schema;
    }

    /** When the insurer's logo was uploaded; empty without one. Used by the login. */
    @Transactional(readOnly = true)
    public Optional<Instant> version(Long insurerId) {
        return repository.findUpdatedAt(insurerId);
    }

    @Transactional
    public Instant replace(byte[] content, String declaredType) {
        String type = validate(content, declaredType);
        Instant now = Instant.now();
        repository.save(new InsurerLogo(currentInsurerId(), content, type, now));
        return now;
    }

    @Transactional
    public void remove() {
        repository.deleteById(currentInsurerId());
    }

    private Long currentInsurerId() {
        String schema = TenantContext.get();
        return insurers.findBySchemaName(schema)
                .orElseThrow(() -> new IllegalStateException("No insurer for schema " + schema))
                .getId();
    }

    private static String validate(byte[] content, String declaredType) {
        if (content == null || content.length == 0) {
            throw new InvalidLogoException("El archivo está vacío.");
        }
        if (content.length > MAX_BYTES) {
            throw new InvalidLogoException("El logo pesa más de 256 KB. Subí una versión más liviana.");
        }
        String type = declaredType == null ? "" : declaredType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        byte[] signature = SIGNATURES.get(type);
        if (signature == null) {
            throw new InvalidLogoException("El logo tiene que ser PNG o JPG.");
        }
        if (content.length < signature.length
                || !Arrays.equals(Arrays.copyOf(content, signature.length), signature)) {
            throw new InvalidLogoException("El archivo no coincide con su formato. Exportalo de nuevo.");
        }
        return type;
    }
}
