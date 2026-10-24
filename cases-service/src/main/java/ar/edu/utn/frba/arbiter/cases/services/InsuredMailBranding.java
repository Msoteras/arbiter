package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerLogoRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import ar.edu.utn.frba.arbiter.common.models.entities.InsurerLogo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The insurer's logo on top of the mails to the insured, embedded (CID) instead of linked: a linked
 * image needs a public URL and most clients block remote images until the reader allows them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InsuredMailBranding {

    static final String CONTENT_ID = "insurer-logo";

    /** The formats mail clients show inline; an older WebP or SVG upload is left out. */
    private static final Set<String> MAIL_TYPES = Set.of("image/png", "image/jpeg");

    private final InsurerRepository insurerRepository;
    private final InsurerLogoRepository insurerLogoRepository;

    /** The mail for the resolved tenant's insurer: the body with the logo above it, or as it was. */
    public Branded brand(String htmlBody) {
        return currentLogo()
                .map(logo -> new Branded(
                        "<p><img src=\"cid:%s\" alt=\"\" height=\"48\" style=\"height:48px;width:auto\"></p>"
                                .formatted(CONTENT_ID) + htmlBody,
                        List.of(new SendGridAdapter.Attachment(
                                "logo" + extension(logo.getContentType()), logo.getContentType(),
                                logo.getContent(), CONTENT_ID))))
                .orElseGet(() -> new Branded(htmlBody, List.of()));
    }

    /** Best-effort: a logo nobody could read must not stop the notification. */
    private Optional<InsurerLogo> currentLogo() {
        // Without a resolved tenant the schema is the common one, which no insurer owns.
        try {
            return insurerRepository.findBySchemaName(TenantContext.get())
                    .flatMap(insurer -> insurerLogoRepository.findById(insurer.getId()))
                    .filter(logo -> MAIL_TYPES.contains(logo.getContentType()));
        } catch (Exception e) {
            log.warn("Could not read the insurer's logo for the mail, it goes without it", e);
            return Optional.empty();
        }
    }

    private static String extension(String contentType) {
        return "image/png".equals(contentType) ? ".png" : ".jpg";
    }

    public record Branded(String htmlBody, List<SendGridAdapter.Attachment> attachments) {
    }
}
