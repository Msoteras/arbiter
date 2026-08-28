// Espejo de ProfileResponse / OnboardingRequest / UpdateProfileRequest de auth-service
// (InsuredProfileController). Son datos de la PERSONA, no del siniestro: viven en Insured,
// no en Case — por eso el wizard de denuncia ya no los pregunta.

/**
 * Consentimiento de análisis forense de imágenes (H0009).
 *
 * El texto y su versión viven juntos a propósito: lo que se persiste en `imageConsentVersion`
 * tiene que poder resolverse de vuelta al texto exacto que la persona leyó. Un consentimiento
 * sin versión ni fecha no sirve como consentimiento — si mañana cambia el texto, no hay forma
 * de saber a qué dijo que sí.
 *
 * Regla al cambiar el texto: se agrega una versión NUEVA, nunca se edita el texto de una
 * versión ya aceptada. Editarlo en el lugar reescribiría retroactivamente lo que aceptaron
 * todos los que ya dieron su consentimiento con esa versión.
 */
export const IMAGE_CONSENT_VERSION = '1.0';

export const IMAGE_CONSENT_SUMMARY =
  'Acepto que mis imágenes se envíen a un proveedor externo de verificación antifraude';

// Corto a propósito, pero sin perder lo que hace al consentimiento informado: que las imágenes
// salen de Arbiter, para qué, y que negarse no tiene costo.
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
  /** Condición de Persona Expuesta Políticamente (UIF/PLA). Viene de la póliza/KYC de la
      aseguradora: se muestra, no se declara acá. */
  pep: boolean;
  imageConsent: boolean;
  /** Versión del texto que aceptó. Null si nunca lo aceptó. */
  imageConsentVersion: string | null;
  /** ISO-8601. Cuándo dio (o revocó) el consentimiento. */
  imageConsentAt: string | null;
  onboardingComplete: boolean;
}

/** POST /api/v1/auth/profile/onboarding — solo se puede llamar una vez (409 si ya está hecho). */
export interface OnboardingRequest {
  email: string;
  phone: string;
  imageConsent: boolean;
  imageConsentVersion: string;
}

/** PATCH /api/v1/auth/profile — parcial: solo los campos que cambian. */
export interface UpdateProfileRequest {
  email?: string;
  phone?: string;
  imageConsent?: boolean;
  imageConsentVersion?: string;
}
