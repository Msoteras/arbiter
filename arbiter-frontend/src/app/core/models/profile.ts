// Mirrors auth-service's insured profile DTOs: person-level data (Insured), not per case.

/**
 * Image forensics consent. The persisted `imageConsentVersion` must resolve to the exact text
 * the person read: when the text changes, add a NEW version, never edit an accepted one.
 * 1.0 also showed a help text on what the provider checks; 1.1 drops it, since telling the
 * insured how the images are verified helps whoever wants to get around it.
 */
const IMAGE_CONSENT_TEXTS = {
  '1.0': 'Acepto que mis imágenes se envíen a un proveedor externo de verificación antifraude',
  '1.1':
    'Acepto que las imágenes de una denuncia se envíen a un proveedor externo de verificación antifraude',
} as const;

export const IMAGE_CONSENT_VERSION = '1.1';

export const IMAGE_CONSENT_SUMMARY = IMAGE_CONSENT_TEXTS[IMAGE_CONSENT_VERSION];

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
