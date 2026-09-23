// Mirrors auth-service's insured profile DTOs: person-level data (Insured), not per case.

/**
 * Image forensics consent. The persisted `imageConsentVersion` must resolve to the exact text
 * the person read: when the text changes, add a NEW version, never edit an accepted one.
 */
export const IMAGE_CONSENT_VERSION = '1.0';

export const IMAGE_CONSENT_SUMMARY =
  'Acepto que mis imágenes se envíen a un proveedor externo de verificación antifraude';

// Informed consent needs: images leave Arbiter, what for, and that declining costs nothing.
export const IMAGE_CONSENT_DETAIL =
  'Tus imágenes pueden compartirse con un servicio externo (fuera de Arbiter) para verificar ' +
  'que no estén publicadas en otro lado. Es opcional: tu denuncia se procesa igual.';

/** GET /api/v1/auth/profile */
export interface InsuredProfile {
  name: string;
  surname: string;
  dni: string;
  email: string | null;
  phone: string | null;
  /** Politically exposed person, from the insurer's KYC: displayed, not declared here. */
  pep: boolean;
  imageConsent: boolean;
  /** Null if never accepted. */
  imageConsentVersion: string | null;
  /** ISO-8601; when consent was given or revoked. */
  imageConsentAt: string | null;
  onboardingComplete: boolean;
}

/** POST /api/v1/auth/profile/onboarding — callable once (409 afterwards). */
export interface OnboardingRequest {
  email: string;
  phone: string;
  imageConsent: boolean;
  imageConsentVersion: string;
}

/** PATCH /api/v1/auth/profile — partial update. */
export interface UpdateProfileRequest {
  email?: string;
  phone?: string;
  imageConsent?: boolean;
  imageConsentVersion?: string;
}
