import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthSessionService } from './auth-session.service';

/**
 * H0009 — primer ingreso del asegurado. Mientras `onboardingComplete` sea false, toda ruta del
 * portal rebota a la pantalla de bienvenida: es lo que hace que retome donde quedó si la
 * abandonó a la mitad (el claim sigue en false, así que el próximo login vuelve acá).
 *
 * Va ENCIMA de `roleGuard`, no en su lugar: aquel resuelve "quién puede entrar", este "si ya
 * completó el primer paso". Se aplica a `/portal/*` salvo a la propia pantalla de onboarding,
 * que quedaría en un ciclo de redirecciones consigo misma.
 *
 * Solo aplica al ASEGURADO. Analista y referente no tienen onboarding: su claim viaja en null
 * y pasan derecho — incluido el referente, que en `roleGuard` tiene acceso total.
 */
export const onboardingGuard: CanActivateFn = () => {
  const session = inject(AuthSessionService).session();

  if (!session || session.rol !== 'ASEGURADO') {
    return true;
  }

  // Solo false bloquea. Un null en un ASEGURADO significa que el backend todavía no emite el
  // claim (o un token viejo de sessionStorage anterior a este cambio): dejarlo pasar es lo
  // correcto — mejor no mostrar el onboarding que encerrar al usuario fuera del portal.
  if (session.onboardingComplete === false) {
    return inject(Router).parseUrl('/portal/onboarding');
  }

  return true;
};

/**
 * El complemento del anterior, para la ruta de onboarding: si ya lo completó, no tiene sentido
 * volver a verlo (criterio de aceptación "terminado el primer ingreso, no vuelve a verlo") —
 * entrar a mano por la URL manda al home del portal.
 */
export const onboardingPendingGuard: CanActivateFn = () => {
  const session = inject(AuthSessionService).session();

  if (session?.rol === 'ASEGURADO' && session.onboardingComplete === false) {
    return true;
  }

  return inject(Router).parseUrl('/portal/home');
};
