package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.auth.dto.InsuredDirectoryEntry;
import ar.edu.utn.frba.arbiter.auth.dto.ProvisioningSummary;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import ar.edu.utn.frba.arbiter.common.tenant.InsurerDbSchema;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Bulk provisioning: creates an account for every policyholder of the referente's insurer with a
 * policy in force and mails them an invitation. The insurer's database is the source of truth for
 * who its insured are.
 */
@Service
@RequiredArgsConstructor
public class InsuredProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(InsuredProvisioningService.class);

    private final InsuredDirectoryAdapter insuredDirectoryAdapter;
    private final InsuredAccountProvisioner provisioner;
    private final SendGridAdapter sendGridAdapter;

    @Value("${arbiter.frontend.base-url:http://localhost:4200}")
    private String frontendBaseUrl;

    /** Paced on purpose: a burst of thousands of mails burns sender reputation and trips SendGrid's rate limits. */
    @Value("${arbiter.provisioning.invite-delay-ms:250}")
    private long inviteDelayMs;

    @Value("${arbiter.provisioning.max-invites-per-run:500}")
    private int maxInvitesPerRun;

    /**
     * {@link TenantContext} is a {@code ThreadLocal} and doesn't follow {@code @Async}: the caller
     * passes the tenant so it can be set again here, or the writes would land in {@code arbiter_common}.
     */
    @Async
    public void provisionAsync(String tenantSchema, Long insurerId) {
        TenantContext.set(tenantSchema);
        try {
            ProvisioningSummary summary = provision(tenantSchema, insurerId);
            log.info("[Provisioning] Alta masiva terminada — tenant={} escaneados={} usuarios_creados={} "
                            + "reusados={} vinculados={} perfiles={} invitados={} omitidos={}",
                    tenantSchema, summary.scanned(), summary.usersCreated(), summary.usersReused(),
                    summary.insurersLinked(), summary.profilesCreated(), summary.invited(),
                    summary.skipped().size());
        } catch (Exception e) {
            log.error("[Provisioning] El alta masiva del tenant {} falló: {}", tenantSchema, e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    ProvisioningSummary provision(String tenantSchema, Long insurerId) {
        List<InsuredDirectoryEntry> directory =
                insuredDirectoryAdapter.findWithPoliciesInForce(InsurerDbSchema.forTenant(tenantSchema));
        log.info("[Provisioning] {} asegurado(s) con póliza vigente en {}", directory.size(), tenantSchema);

        List<String> skipped = new ArrayList<>();
        int created = 0;
        int reused = 0;
        int linked = 0;
        int profiles = 0;
        int invited = 0;

        for (InsuredDirectoryEntry entry : directory) {
            if (entry.email() == null || entry.email().isBlank()) {
                skipped.add("%s %s (%s): la aseguradora no tiene su email"
                        .formatted(entry.name(), entry.surname(), entry.dni()));
                continue;
            }

            try {
                InsuredAccountProvisioner.Outcome outcome = provisioner.provisionOne(entry, insurerId);

                if (outcome.userCreated()) {
                    created++;
                } else {
                    reused++;
                }
                if (outcome.insurerLinked()) {
                    linked++;
                }
                if (outcome.profileCreated()) {
                    profiles++;
                }

                if (outcome.inviteToken() != null && invited < maxInvitesPerRun) {
                    if (sendInvite(entry, outcome.inviteToken())) {
                        invited++;
                    } else {
                        skipped.add("%s (%s): la cuenta quedó creada pero el mail no salió"
                                .formatted(entry.email(), entry.dni()));
                    }
                }
            } catch (Exception e) {
                // provisionOne has its own transaction, so only this person's changes rolled back.
                log.warn("[Provisioning] No se pudo dar de alta a {} ({}): {}",
                        entry.email(), entry.dni(), e.getMessage());
                skipped.add("%s (%s): %s".formatted(entry.email(), entry.dni(), e.getMessage()));
            }
        }

        return new ProvisioningSummary(
                directory.size(), created, reused, linked, profiles, invited, List.copyOf(skipped));
    }

    /**
     * Unlike the single-user invitation, a failed send doesn't undo the account: the run is
     * unattended, it's reported in the summary and {@code UserService.resendInvite} recovers it.
     */
    private boolean sendInvite(InsuredDirectoryEntry entry, String inviteToken) {
        try {
            sendGridAdapter.send(entry.email(), "Activá tu cuenta en Arbiter",
                    invitationEmailBody(entry.name(), inviteToken));
            pauseBetweenInvites();
            return true;
        } catch (RuntimeException e) {
            log.warn("[Provisioning] No salió la invitación a {}: {}", entry.email(), e.getMessage());
            return false;
        }
    }

    private void pauseBetweenInvites() {
        if (inviteDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(inviteDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String invitationEmailBody(String nombre, String token) {
        String activationUrl = frontendBaseUrl + "/activate-account?token=" + token;
        return """
                <p>Hola %s,</p>
                <p>Tu aseguradora te dio de alta en Arbiter, donde vas a poder denunciar siniestros y
                seguir tus expedientes. Hacé clic en el siguiente link para elegir tu contraseña y
                activar tu cuenta:</p>
                <p><a href="%s">%s</a></p>
                <p>Este link vence en 48 horas.</p>
                """.formatted(nombre, activationUrl, activationUrl);
    }
}
