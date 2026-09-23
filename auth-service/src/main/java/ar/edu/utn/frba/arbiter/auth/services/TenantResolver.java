package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.auth.models.repositories.UserInsurerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Resolves a user's insurers from the common schema, so it works before any tenant is set. */
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

    /** Known simplification: a user in several insurers is routed to the first one. */
    public Optional<Insurer> primaryInsurerFor(Long userId) {
        return insurerIdsFor(userId).stream()
                .findFirst()
                .flatMap(insurerRepository::findById);
    }
}
