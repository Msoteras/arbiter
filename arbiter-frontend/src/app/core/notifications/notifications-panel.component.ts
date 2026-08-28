import { ChangeDetectionStrategy, Component, computed, inject, output } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthSessionService } from '../auth/auth-session.service';
import { NotificationsService } from './notifications.service';
import { formatDateTime } from '../util/datetime';
import { EmptyStateComponent } from '../../shared/ui/empty-state/empty-state.component';
import { InlineLoadingComponent } from '../../shared/ui/inline-loading/inline-loading.component';

/**
 * Headline per notification type. Deliberately not `estadoLabel` — that's the analyst's technical
 * vocabulary. These only fire for an insured, so they say "siniestro" like the rest of the portal.
 */
const NOTIFICATION_TITLES: Record<string, string> = {
  PENDING_CLASSIFICATION: 'Recibimos tu denuncia',
  AWAITING_DOCUMENTATION: 'Necesitamos documentación',
  APPROVED: 'Tu siniestro fue aprobado',
  REJECTED: 'Novedades sobre tu siniestro',
};

/**
 * Panel de notificaciones que cuelga de la campana. Es un componente y no markup del shell porque
 * lo usan las dos barras (el topbar del portal del asegurado y el chrome de los roles internos)
 * y el contenido es idéntico: solo cambia de qué campana cuelga.
 *
 * Quién lo abre y lo cierra es el shell; acá adentro solo se avisa con `close` cuando el propio
 * panel resuelve la interacción (seguir el link a un expediente).
 */
@Component({
  selector: 'app-notifications-panel',
  imports: [RouterLink, EmptyStateComponent, InlineLoadingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // El click no sale del panel: el shell cierra los desplegables con un listener en document.
  host: { '(click)': '$event.stopPropagation()' },
  template: `
    <div class="notif-panel" role="dialog" aria-label="Notificaciones">
      <div class="notif-panel-head">
        <span class="notif-panel-title">Notificaciones</span>
      </div>

      @if (notifications.loading()) {
        <div class="notif-panel-body">
          <app-inline-loading message="Buscando novedades…" />
        </div>
      } @else if (notifications.items().length === 0) {
        <div class="notif-panel-body">
          <app-empty-state message="No tenés notificaciones" [sub]="emptyHint()" />
        </div>
      } @else {
        <ul class="notif-list">
          @for (item of notifications.items(); track item.id) {
            <li class="notif-item" [class.notif-item--unread]="!item.read">
              <div class="notif-item__head">
                <span class="t-field-label">{{ title(item.type) }}</span>
                @if (item.createdAt) {
                  <time class="t-note" [attr.datetime]="item.createdAt">{{
                    formatDateTime(item.createdAt)
                  }}</time>
                }
              </div>
              <p class="t-body">{{ item.content }}</p>
              @if (item.caseId) {
                <a
                  class="notif-item__link t-note"
                  [routerLink]="caseLink(item.caseId)"
                  [queryParams]="item.insurerSlug ? { insurer: item.insurerSlug } : {}"
                  (click)="close.emit()"
                >
                  Ver {{ caseNoun() }} #{{ item.caseId }} ›
                </a>
              }
            </li>
          }
        </ul>
      }
    </div>
  `,
  styles: `
    :host {
      /* Cuelga de la campana, que es quien pone el contexto de posicionamiento. */
      position: absolute;
      top: calc(100% + var(--space-2));
      right: 0;
      z-index: 60;
      width: 380px;
      max-width: 88vw;
    }

    .notif-panel {
      padding: var(--space-1);
      background: var(--surface);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-card);
      box-shadow: var(--shadow-modal);
    }
    .notif-panel-head {
      padding: var(--space-2) var(--space-3);
      border-bottom: 1px solid var(--border-subtle);
      margin-bottom: var(--space-1);
    }
    .notif-panel-title {
      font-size: var(--font-size-2xs);
      font-weight: var(--font-weight-medium);
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: var(--text-muted);
    }
    .notif-panel-body {
      padding: var(--space-2);
    }

    .notif-list {
      list-style: none;
      margin: 0;
      padding: var(--space-1);
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      /* The list is the panel's body: it scrolls on its own instead of growing past the viewport
         when there are many. */
      max-height: 60vh;
      overflow-y: auto;
    }

    .notif-item {
      padding: var(--space-3);
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-card);
      background: var(--surface);
    }

    /* Unread is marked with the brand accent, the same role the rest of the app uses for selection
       — not with the status palette, which is reserved for the case's own state. */
    .notif-item--unread {
      background: var(--selected-bg);
      border-color: var(--selected-border);
    }

    .notif-item__head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--space-3);
      margin-bottom: var(--space-1);
    }

    .notif-item__link {
      display: inline-block;
      margin-top: var(--space-2);
      color: var(--accent-fg);
      text-decoration: none;
    }
    .notif-item__link:hover {
      text-decoration: underline;
    }
  `,
})
export class NotificationsPanelComponent {
  private readonly session = inject(AuthSessionService);
  protected readonly notifications = inject(NotificationsService);

  /** Lo emite cuando el propio panel resuelve la interacción; cerrarlo es del shell. */
  readonly close = output<void>();

  /** Same wording the insured sees on the case: no classification, no score. */
  protected title(type: string): string {
    return NOTIFICATION_TITLES[type] ?? `Novedades de tu ${this.caseNoun()}`;
  }

  /**
   * What to call a case in front of whoever is reading. The portal says "siniestro" everywhere —
   * the expediente is the administrative case the analyst works on, not what the insured filed.
   * The panel is shared by every role, so the word can't be hardcoded in the template.
   */
  protected readonly caseNoun = computed(() =>
    this.session.session()?.rol === 'ASEGURADO' ? 'siniestro' : 'expediente',
  );

  /** Only the insured gets notified: promising an analyst a mail would be false. */
  protected readonly emptyHint = computed(() =>
    this.session.session()?.rol === 'ASEGURADO'
      ? 'Te avisamos acá y por mail cuando tu siniestro tenga novedades.'
      : 'Los avisos sobre los expedientes que trabajás van a aparecer acá.',
  );

  /** The app's es-AR formatter, not Angular's DatePipe, which defaults to en-US. */
  protected readonly formatDateTime = formatDateTime;

  /**
   * A case lives at a different path per role — the insured's is the read-only tracking screen,
   * and {@code /cases/:id} is guarded for analista/referente. Linking to the wrong one doesn't 404:
   * the guard bounces to the role's home, so the notification looks like it goes nowhere.
   */
  protected caseLink(caseId: number): string {
    return this.session.session()?.rol === 'ASEGURADO'
      ? `/portal/cases/${caseId}`
      : `/cases/${caseId}`;
  }
}
