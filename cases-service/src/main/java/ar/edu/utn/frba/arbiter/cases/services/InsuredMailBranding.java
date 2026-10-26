package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerLogoRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.Optional;
import java.util.Set;

/**
 * The signature of the mails to the insured: the insurer's logo and name. The logo is an image by
 * URL, not an attachment: embedded (CID), mail clients list it as one more attached file.
 */
@Slf4j
@Component
public class InsuredMailBranding {

    static final String DEFAULT_SIGNATURE = "<p>Arbiter</p>";

    /** The formats mail clients show; an older WebP or SVG upload signs as if there were none. */
    private static final Set<String> MAIL_TYPES = Set.of("image/png", "image/jpeg");

    private final InsurerRepository insurerRepository;
    private final InsurerLogoRepository insurerLogoRepository;
    private final String publicBaseUrl;

    public InsuredMailBranding(InsurerRepository insurerRepository,
                               InsurerLogoRepository insurerLogoRepository,
                               @Value("${arbiter.email.public-base-url}") String publicBaseUrl) {
        this.insurerRepository = insurerRepository;
        this.insurerLogoRepository = insurerLogoRepository;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    /** For the resolved tenant's insurer; "Arbiter" when it has no logo or it can't be read. */
    public String signature() {
        return insurerWithLogo()
                .map(insurer -> """
                        <p style="margin-top:24px"><img src="%s/api/v1/auth/public/insurers/%s/logo" \
                        alt="%s" height="40" style="display:block;height:40px;width:auto;border:0"></p>
                        <p>%s</p>
                        """.formatted(publicBaseUrl, InsurerSlug.of(insurer),
                        HtmlUtils.htmlEscape(insurer.getName()), HtmlUtils.htmlEscape(insurer.getName())))
                .orElse(DEFAULT_SIGNATURE);
    }

    /** Best-effort: a logo nobody could read must not stop the notification. */
    private Optional<Insurer> insurerWithLogo() {
        // Without a resolved tenant the schema is the common one, which no insurer owns.
        try {
            return insurerRepository.findBySchemaName(TenantContext.get())
                    .filter(insurer -> insurerLogoRepository.findById(insurer.getId())
                            .filter(logo -> MAIL_TYPES.contains(logo.getContentType()))
                            .isPresent());
        } catch (Exception e) {
            log.warn("Could not read the insurer's logo for the mail, it signs without it", e);
            return Optional.empty();
        }
    }
}
