package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserInsurerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Resolves which insurer(s) a user belongs to, from the common schema (works without a
 * tenant already set in {@link ar.edu.utn.frba.arbiter.auth.config.tenant.TenantContext}
 * — {@code user_insurer} and {@code insurer} both live there).
 */
@Component
@RequiredArgsConstructor
public class TenantResolver {

    private final UserInsurerRepository userInsurerRepository;
    private final InsurerRepository insurerRepository;

    public List<Long> insurerIdsFor(Long userId) {
        return userInsurerRepository.findByUserId(userId).stream()
                .map(ui -> ui.getInsurerId())
                .toList();
    }

    /**
     * Which insurer to route this session's requests to. A user belonging to more than
     * one insurer (the demo seed's Martina Soteras is exactly this case) isn't
     * disambiguated yet — picking the first one is a known simplification until the
     * login UX for that case is decided (see README-multitenant.md, "Resolución del
     * tenant por request").
     */
    public Optional<Insurer> primaryInsurerFor(Long userId) {
        return insurerIdsFor(userId).stream()
                .findFirst()
                .flatMap(insurerRepository::findById);
    }
}
