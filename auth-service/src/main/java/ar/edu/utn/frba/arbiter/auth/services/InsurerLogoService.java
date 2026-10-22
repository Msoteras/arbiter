package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.exceptions.InvalidLogoException;
import ar.edu.utn.frba.arbiter.auth.exceptions.LogoNotFoundException;
import ar.edu.utn.frba.arbiter.auth.models.entities.InsurerLogo;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsurerLogoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The logo of the insurer the request is for (the tenant resolved from the JWT). Every user of the
 * insurer reads it; only the referent changes it (enforced by the controller).
 */
@Service
@RequiredArgsConstructor
public class InsurerLogoService {

    /** A logo is a small mark: anything bigger is a photo or an unoptimized export. */
    public static final int MAX_BYTES = 256 * 1024;

    /** The declared type has to match the file's first bytes: the header alone is the client's word. */
    private static final Map<String, byte[]> SIGNATURES = Map.of(
            "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'},
            "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
            "image/webp", new byte[]{'R', 'I', 'F', 'F'});
    private static final String SVG = "image/svg+xml";

    private final InsurerLogoRepository repository;

    @Transactional(readOnly = true)
    public InsurerLogo get() {
        return repository.findById(InsurerLogo.SINGLETON_ID).orElseThrow(LogoNotFoundException::new);
    }

    /** When the current logo was uploaded; empty without one. */
    @Transactional(readOnly = true)
    public Optional<Instant> version() {
        return repository.findUpdatedAt();
    }

    @Transactional
    public Instant replace(byte[] content, String declaredType) {
        String type = validate(content, declaredType);
        Instant now = Instant.now();
        repository.save(new InsurerLogo(InsurerLogo.SINGLETON_ID, content, type, now));
        return now;
    }

    @Transactional
    public void remove() {
        repository.deleteById(InsurerLogo.SINGLETON_ID);
    }

    private static String validate(byte[] content, String declaredType) {
        if (content == null || content.length == 0) {
            throw new InvalidLogoException("El archivo está vacío.");
        }
        if (content.length > MAX_BYTES) {
            throw new InvalidLogoException("El logo pesa más de 256 KB. Subí una versión más liviana.");
        }
        String type = declaredType == null ? "" : declaredType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (SVG.equals(type)) {
            String head = new String(content, 0, Math.min(content.length, 1024), StandardCharsets.UTF_8);
            if (!head.contains("<svg")) {
                throw new InvalidLogoException("El archivo no es un SVG válido.");
            }
            return type;
        }
        byte[] signature = SIGNATURES.get(type);
        if (signature == null) {
            throw new InvalidLogoException("El logo tiene que ser PNG, JPG, WebP o SVG.");
        }
        if (content.length < signature.length
                || !Arrays.equals(Arrays.copyOf(content, signature.length), signature)) {
            throw new InvalidLogoException("El archivo no coincide con su formato. Exportalo de nuevo.");
        }
        return type;
    }
}
