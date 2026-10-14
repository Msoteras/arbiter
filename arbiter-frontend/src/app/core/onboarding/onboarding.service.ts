import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, map, tap } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ToastService } from '../../shared/ui/toast/toast.service';
import {
  ONBOARDING_STEP_LINE,
  OnboardingRoute,
  OnboardingStep,
  OnboardingStepId,
  onboardingStepArea,
  onboardingStepKey,
  onboardingStepRoute,
  onboardingStepTitle,
} from './onboarding';

/** Waits for the burst of requests one save can fire (a coverage, its exclusions, its rules). */
const REFRESH_DEBOUNCE_MS = 400;

/** A screen that renders its control later than this, e.g. after a slow load, is not pointed at. */
const FOCUS_TTL_MS = 15_000;

/**
 * A request to point at the control of a step on its screen. `id` tells two requests apart;
 * `sameUrl` says the screen was already open at that address, so it has to reopen the step itself.
 */
export interface OnboardingFocus {
  step: OnboardingStepId;
  id: number;
  at: number;
  sameUrl: boolean;
}

/**
 * The referent's setup checklist, shared by the home card, the menu dots and the "step complete"
 * toast. Loaded once per referent session and refreshed after every save of a setup section.
 */
@Injectable({ providedIn: 'root' })
export class OnboardingService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly base = `${environment.apiBaseUrl}/rules/onboarding`;

  /** Null until the first load answers, and for anyone who isn't a referent. */
  readonly steps = signal<OnboardingStep[] | null>(null);
  readonly failed = signal(false);
  private refreshTimer: ReturnType<typeof setTimeout> | null = null;

  /** The last step opened from the checklist or the toast; the screens watch it. */
  readonly focusRequest = signal<OnboardingFocus | null>(null);
  private focusSeq = 0;
  private claimedFocusId: number | null = null;

  /** The branch a branch-level step without a branch of its own opens on. */
  private readonly firstBranchId = computed(
    () => this.steps()?.find((s) => s.step === 'COVERAGES')?.branchId ?? null,
  );

  /** Menu entries with pending steps. */
  readonly pendingAreas = computed(() => {
    const areas = new Set<'rules' | 'users'>();
    for (const s of this.steps() ?? []) {
      if (s.status === 'PENDING') {
        areas.add(onboardingStepArea(s.step));
      }
    }
    return areas;
  });

  load(): void {
    this.failed.set(false);
    this.fetch().subscribe({ error: () => this.failed.set(true) });
  }

  clear(): void {
    this.steps.set(null);
    this.failed.set(false);
  }

  routeOf(step: OnboardingStep): OnboardingRoute {
    return onboardingStepRoute(step, this.firstBranchId());
  }

  /**
   * Goes to a step's screen and points at its control. The highlight also covers the case where
   * the screen is already open at that same address, so the navigation itself changes nothing.
   */
  open(step: OnboardingStep): Promise<boolean> {
    const route = this.routeOf(step);
    const target = this.router.createUrlTree([route.path], { queryParams: route.queryParams });
    this.requestFocus(step.step, this.router.url === this.router.serializeUrl(target));
    return this.router.navigateByUrl(target);
  }

  /** For links that navigate on their own (`routerLink`): only the pointing part of `open`. */
  requestFocus(step: OnboardingStepId, sameUrl = false): void {
    this.focusRequest.set({ step, id: ++this.focusSeq, at: Date.now(), sameUrl });
  }

  /** True once per request, for the first control of that step to render while it is fresh. */
  claimFocus(step: OnboardingStepId): boolean {
    const request = this.focusRequest();
    if (
      !request ||
      request.step !== step ||
      request.id === this.claimedFocusId ||
      Date.now() - request.at > FOCUS_TTL_MS
    ) {
      return false;
    }
    this.claimedFocusId = request.id;
    return true;
  }

  setSkipped(step: OnboardingStepId, skipped: boolean): Observable<void> {
    return this.http
      .put<void>(`${this.base}/steps/${step}`, { skipped })
      .pipe(tap(() => this.load()));
  }

  reset(): Observable<void> {
    return this.http.post<void>(`${this.base}/resets`, {}).pipe(tap(() => this.load()));
  }

  /**
   * After a save of a setup section: reloads the checklist and, if the save completed a step,
   * says so and offers the next pending one. Only for a referent whose checklist is loaded.
   */
  refreshAfterSave(): void {
    if (this.steps() === null) {
      return;
    }
    if (this.refreshTimer) {
      clearTimeout(this.refreshTimer);
    }
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = null;
      const before = new Map((this.steps() ?? []).map((s) => [onboardingStepKey(s), s.status]));
      this.fetch().subscribe({
        next: (after) => this.announceCompleted(before, after),
        error: () => undefined,
      });
    }, REFRESH_DEBOUNCE_MS);
  }

  private fetch(): Observable<OnboardingStep[]> {
    return this.http.get<{ steps: OnboardingStep[] }>(this.base).pipe(
      map((res) => res.steps),
      tap((steps) => this.steps.set(steps)),
    );
  }

  private announceCompleted(before: Map<string, string>, after: OnboardingStep[]): void {
    const completed = after.find(
      (s) => s.status === 'DONE' && before.get(onboardingStepKey(s)) === 'PENDING',
    );
    if (!completed) {
      return;
    }
    const message = `Paso completo: ${onboardingStepTitle(completed)}`;
    const next = after.find((s) => s.status === 'PENDING');
    if (!next) {
      this.toast.show(message, 'ok');
      return;
    }
    this.toast.show(message, 'ok', 8000, {
      action: { label: 'Siguiente', run: () => this.goToNext(next) },
    });
  }

  /** Lands on the next step and says what to do there, with the checklist's own words. */
  private goToNext(step: OnboardingStep): void {
    void this.open(step).then(() =>
      this.toast.show(onboardingStepTitle(step), 'info', 10000, {
        description: ONBOARDING_STEP_LINE[step.step],
      }),
    );
  }
}
