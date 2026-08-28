package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.auth.dto.LoginResponse;
import ar.edu.utn.frba.arbiter.auth.dto.OnboardingRequest;
import ar.edu.utn.frba.arbiter.auth.dto.ProfileResponse;
import ar.edu.utn.frba.arbiter.auth.dto.UpdateProfileRequest;
import ar.edu.utn.frba.arbiter.auth.exceptions.InsuredProfileNotFoundException;
import ar.edu.utn.frba.arbiter.auth.exceptions.OnboardingAlreadyCompleteException;
import ar.edu.utn.frba.arbiter.auth.models.repositories.InsuredRepository;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class InsuredProfileService {

    private final InsuredRepository insuredRepository;
    private final JwtService jwtService;
    private final TenantResolver tenantResolver;

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(User user) {
        Insured insured = findInsuredOrThrow(user);
        return toProfileResponse(insured);
    }

    @Transactional
    public LoginResponse completeOnboarding(User user, OnboardingRequest request) {
        Insured insured = findInsuredOrThrow(user);

        if (insured.isOnboardingComplete()) {
            throw new OnboardingAlreadyCompleteException();
        }

        if (request.email() != null) {
            insured.setEmail(request.email());
        }
        if (request.phone() != null) {
            insured.setPhone(request.phone());
        }

        applyConsent(insured, request.imageConsent(), request.imageConsentVersion());

        insured.setOnboardingComplete(true);
        insured.setOnboardingCompletedAt(Instant.now());
        insuredRepository.save(insured);

        return reissueToken(user, insured);
    }

    @Transactional
    public LoginResponse updateProfile(User user, UpdateProfileRequest request) {
        Insured insured = findInsuredOrThrow(user);

        if (request.email() != null) {
            insured.setEmail(request.email());
        }
        if (request.phone() != null) {
            insured.setPhone(request.phone());
        }
        if (request.imageConsent() != null) {
            applyConsent(insured, request.imageConsent(), request.imageConsentVersion());
        }

        insuredRepository.save(insured);
        return reissueToken(user, insured);
    }

    private void applyConsent(Insured insured, boolean consent, String version) {
        insured.setImageConsent(consent);
        insured.setImageConsentVersion(version);
        insured.setImageConsentAt(Instant.now());
    }

    private Insured findInsuredOrThrow(User user) {
        return insuredRepository.findByUserId(user.getId())
                .orElseThrow(() -> new InsuredProfileNotFoundException(user.getEmail()));
    }

    private LoginResponse reissueToken(User user, Insured insured) {
        List<Long> insurerIds = tenantResolver.insurerIdsFor(user.getId());
        Optional<Insurer> primaryInsurer = tenantResolver.primaryInsurerFor(user.getId());
        String tenantSchema = primaryInsurer.map(Insurer::getSchemaName).orElse(null);

        JwtService.IssuedToken token = jwtService.issue(
                user, UserRole.ASEGURADO,
                insured.getName(), insured.getSurname(), insured.getDni(),
                insured.isOnboardingComplete(), insurerIds, tenantSchema);

        return new LoginResponse(
                token.token(), token.expiresAt(),
                user.getId(), user.getEmail(), UserRole.ASEGURADO,
                insured.getName(), insured.getSurname(), insured.getDni(),
                insured.isOnboardingComplete());
    }

    private ProfileResponse toProfileResponse(Insured insured) {
        return new ProfileResponse(
                insured.getName(),
                insured.getSurname(),
                insured.getDni(),
                insured.getEmail(),
                insured.getPhone(),
                insured.isPep(),
                insured.isImageConsent(),
                insured.getImageConsentVersion(),
                insured.getImageConsentAt(),
                insured.isOnboardingComplete());
    }
}
