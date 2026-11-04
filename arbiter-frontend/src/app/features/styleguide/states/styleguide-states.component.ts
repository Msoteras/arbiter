import { ChangeDetectionStrategy, Component, signal } from '@angular/core';

import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { StatusTimelineComponent } from '../../../shared/ui/status-timeline/status-timeline.component';
import { TabOption, TabsComponent } from '../../../shared/ui/tabs/tabs.component';
import {
  CaseStatus,
  caseStatusLabel,
  caseStatusTone,
  isFinalStatus,
  isPausingStatus,
  workStatusLabel,
} from '../../../core/models/case-status';
import {
  InsuredStatusCode,
  insuredNextStep,
  insuredStatusTone,
} from '../../../core/models/insured-status';
import { UserStatus, userStatusLabel, userStatusTone } from '../../../core/models/user-status';
import { ClassificationStage, stageDef } from '../../../core/models/engine';
import {
  FraudRecord,
  fraudRecordStatusLabel,
  fraudRecordStatusTone,
} from '../../../core/models/fraud-record';
import { StatusTransition } from '../../../core/models/case';
import { StatusTone } from '../../../core/models/status-tone';

type MachineId = 'case' | 'insured' | 'settlement' | 'user' | 'engine' | 'other';

interface StateSample {
  label: string;
  tone: StatusTone;
  /** The enum literal, or a description when the state has no literal of its own. */
  code: string;
  note?: string;
  busy?: boolean;
  implicit?: boolean;
}

interface TimelineSample {
  title: string;
  current: CaseStatus;
  history: StatusTransition[];
}

const CASE_STATUSES: CaseStatus[] = [
  'PENDING_CLASSIFICATION',
  'CLASSIFICATION_FAILED',
  'PENDING_ANALYST_REVIEW',
  'AWAITING_DOCUMENTATION',
  'PENDING_EXPERT_REPORT',
  'PENDING_REPAIR',
  'APPROVED',
  'REJECTED',
  'PRESCRIBED',
  'LAPSED',
  'WITHDRAWN',
  'VOIDED',
];

/** Copy of the `case_status.insured_status*` seed in db/init-multitenant.sql: the portal gets it from there. */
const INSURED_VIEW: Record<CaseStatus, { code: InsuredStatusCode; label: string }> = {
  PENDING_CLASSIFICATION: { code: 'UNDER_ANALYSIS', label: 'En análisis' },
  PENDING_ANALYST_REVIEW: { code: 'UNDER_ANALYSIS', label: 'En análisis' },
  CLASSIFICATION_FAILED: { code: 'UNDER_ANALYSIS', label: 'En análisis' },
  AWAITING_DOCUMENTATION: { code: 'AWAITING_DOCUMENTATION', label: 'Falta documentación' },
  PENDING_EXPERT_REPORT: { code: 'UNDER_VERIFICATION', label: 'En peritaje' },
  PENDING_REPAIR: { code: 'UNDER_REPAIR', label: 'En reparación' },
  APPROVED: { code: 'APPROVED', label: 'Aprobado' },
  REJECTED: { code: 'REJECTED', label: 'Rechazado' },
  PRESCRIBED: { code: 'REJECTED', label: 'Rechazado' },
  LAPSED: { code: 'LAPSED', label: 'Caducado' },
  WITHDRAWN: { code: 'CLOSED', label: 'Cerrado' },
  VOIDED: { code: 'CLOSED', label: 'Cerrado' },
};

const ENGINE_STAGES: ClassificationStage[] = [
  'POLICY',
  'HISTORY',
  'RULES',
  'EXCLUSION',
  'DOCUMENT',
  'RULE_EVALUATION',
  'FAST_TRACK',
  'DOCUMENT_SCHEDULE',
  'MODEL',
  'IMAGES',
  'RISK',
];

function move(
  fromStatus: CaseStatus | null,
  toStatus: CaseStatus,
  actor: StatusTransition['actor'],
  reason: string,
  changedAt: string,
): StatusTransition {
  return { fromStatus, toStatus, actor, reason, observation: null, changedAt };
}

/**
 * Every state of the app's state machines, drawn with the real components and the real label and
 * tone helpers. States marked implicit have no enum literal: the code derives them from other fields.
 */
@Component({
  selector: 'app-styleguide-states',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BadgeComponent, ButtonComponent, StatusTimelineComponent, TabsComponent],
  template: `
    <app-tabs
      [tabs]="machines"
      [(value)]="machine"
      ariaLabel="Máquinas de estado"
      [scrollable]="true"
    />

    <div class="panel">
      @switch (machine()) {
        @case ('case') {
          <ul class="grid">
            @for (s of caseSamples; track s.code) {
              <li class="cell">
                <app-badge [tone]="s.tone" [busy]="!!s.busy">{{ s.label }}</app-badge>
                <span class="mono code">{{ s.code }}</span>
                @if (s.note) {
                  <span class="t-note">{{ s.note }}</span>
                }
              </li>
            }
          </ul>

          <h4 class="t-section-label sub">Recorridos</h4>
          <div class="timelines">
            @for (t of timelines; track t.title) {
              <section class="timeline">
                <h5 class="t-field-label">{{ t.title }}</h5>
                <app-status-timeline [history]="t.history" [currentStatus]="t.current" />
              </section>
            }
          </div>
        }

        @case ('insured') {
          <ul class="map">
            @for (row of insuredRows; track row.internal) {
              <li class="map-row">
                <app-badge [tone]="internalTone(row.internal)">{{
                  internalLabel(row.internal)
                }}</app-badge>
                <span class="arrow" aria-hidden="true">→</span>
                <app-badge [tone]="row.tone">{{ row.label }}</app-badge>
                <span class="t-note">{{ row.next }}</span>
              </li>
            }
          </ul>
        }

        @case ('settlement') {
          <ul class="grid">
            @for (s of settlementSamples; track s.code) {
              <li class="cell">
                <app-badge [tone]="s.tone" [variant]="s.implicit ? 'dashed' : 'solid'">
                  {{ s.label }}
                </app-badge>
                <span class="mono code">{{ s.code }}</span>
                @if (s.note) {
                  <span class="t-note">{{ s.note }}</span>
                }
              </li>
            }
          </ul>
          <h4 class="t-section-label sub">Derivación</h4>
          <ul class="grid">
            @for (s of referralSamples; track s.code) {
              <li class="cell">
                <app-badge [tone]="s.tone" variant="dashed">{{ s.label }}</app-badge>
                <span class="mono code">{{ s.code }}</span>
              </li>
            }
          </ul>
        }

        @case ('user') {
          <ul class="grid">
            @for (s of userSamples; track s.code) {
              <li class="cell">
                <app-badge [tone]="s.tone" [variant]="s.implicit ? 'dashed' : 'solid'">
                  {{ s.label }}
                </app-badge>
                <span class="mono code">{{ s.code }}</span>
                @if (s.note) {
                  <span class="t-note">{{ s.note }}</span>
                }
              </li>
            }
          </ul>
        }

        @case ('engine') {
          <ol class="stages">
            <li class="stage">
              <app-badge tone="neutral">En cola</app-badge>
              <span class="mono code">QUEUED</span>
            </li>
            @for (s of engineStages; track s.stage) {
              <li class="stage">
                <app-badge tone="info" [busy]="true">{{ s.text }}</app-badge>
                <span class="mono code">{{ s.stage }} · {{ s.actor }}</span>
              </li>
            }
            <li class="stage">
              <app-badge tone="ok">Clasificado</app-badge>
              <span class="mono code">FINISHED</span>
            </li>
            <li class="stage">
              <app-badge tone="warning">Clasificación fallida</app-badge>
              <span class="mono code">FAILED</span>
            </li>
          </ol>
        }

        @case ('other') {
          <h4 class="t-section-label sub">Botón de guardado</h4>
          <div class="row">
            <app-button
              [saving]="saving()"
              [saveFailed]="saveFailed()"
              (click)="simulateSave(false)"
            >
              Guardar
            </app-button>
            <app-button variant="secondary" (click)="simulateSave(true)"
              >Simular un error</app-button
            >
          </div>

          <h4 class="t-section-label sub">Antecedente de fraude</h4>
          <ul class="grid">
            @for (r of fraudSamples; track $index) {
              <li class="cell">
                <app-badge [tone]="fraudTone(r)">{{ fraudLabel(r) }}</app-badge>
              </li>
            }
          </ul>

          <h4 class="t-section-label sub">Puesta en marcha</h4>
          <ul class="grid">
            @for (s of onboardingSamples; track s.code) {
              <li class="cell">
                <app-badge [tone]="s.tone" [variant]="s.implicit ? 'dashed' : 'solid'">
                  {{ s.label }}
                </app-badge>
                <span class="mono code">{{ s.code }}</span>
              </li>
            }
          </ul>
        }
      }
    </div>
  `,
  styles: `
    @use 'media';

    :host {
      display: block;
    }
    .panel {
      padding-top: var(--space-4);
    }
    .grid {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(14rem, 1fr));
      gap: var(--space-3);
    }
    .cell {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: var(--space-2);
      padding: var(--space-3);
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-card);
      background: var(--surface);
    }
    .code {
      font-size: var(--font-size-2xs);
      color: var(--text-tertiary);
    }
    .sub {
      margin: var(--space-5) 0 var(--space-3);
    }
    .timelines {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(16rem, 1fr));
      gap: var(--space-4);
    }
    .timeline h5 {
      margin: 0 0 var(--space-2);
    }
    .map {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
    }
    .map-row {
      display: grid;
      grid-template-columns: 13rem auto 10rem 1fr;
      align-items: center;
      gap: var(--space-3);
    }
    .arrow {
      color: var(--text-muted);
    }
    .stages {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
    }
    .stage {
      display: flex;
      align-items: center;
      gap: var(--space-3);
    }
    .row {
      display: flex;
      gap: var(--space-3);
      flex-wrap: wrap;
      align-items: center;
    }
    @include media.phone {
      .map-row {
        grid-template-columns: 1fr;
        gap: var(--space-1);
      }
      .arrow {
        display: none;
      }
    }
  `,
})
export class StyleguideStatesComponent {
  protected readonly machines: TabOption[] = [
    { id: 'case', label: 'Expediente' },
    { id: 'insured', label: 'Vista del asegurado' },
    { id: 'settlement', label: 'Liquidación y derivación' },
    { id: 'user', label: 'Usuario' },
    { id: 'engine', label: 'Motor' },
    { id: 'other', label: 'Otros' },
  ];
  protected readonly machine = signal<MachineId>('case');

  protected readonly caseSamples: StateSample[] = [
    ...CASE_STATUSES.map((status) => ({
      code: status,
      label: caseStatusLabel(status),
      tone: caseStatusTone(status),
      busy: status === 'PENDING_CLASSIFICATION',
      note: isFinalStatus(status)
        ? 'Final: solo sale por reapertura.'
        : isPausingStatus(status)
          ? 'Congela el plazo del art. 56.'
          : undefined,
    })),
    {
      code: 'PENDING_ANALYST_REVIEW + PENDING_AUTHORIZATION',
      label: workStatusLabel('PENDING_ANALYST_REVIEW', 'PENDING_AUTHORIZATION'),
      tone: caseStatusTone('PENDING_ANALYST_REVIEW'),
      note: 'Sale de combinar el expediente con la liquidación.',
    },
  ];

  protected readonly timelines: TimelineSample[] = [
    {
      title: 'Aprobado sin pedidos',
      current: 'APPROVED',
      history: [
        move(
          null,
          'PENDING_CLASSIFICATION',
          'INSURED',
          'denuncia registrada',
          '2026-10-01T12:00:00Z',
        ),
        move(
          'PENDING_CLASSIFICATION',
          'PENDING_ANALYST_REVIEW',
          'SYSTEM',
          'clasificación lista',
          '2026-10-01T12:08:00Z',
        ),
        move('PENDING_ANALYST_REVIEW', 'APPROVED', 'ANALYST', 'aprobado', '2026-10-02T15:30:00Z'),
      ],
    },
    {
      title: 'Con pedido de documentación',
      current: 'PENDING_ANALYST_REVIEW',
      history: [
        move(
          null,
          'PENDING_CLASSIFICATION',
          'INSURED',
          'denuncia registrada',
          '2026-10-01T12:00:00Z',
        ),
        move(
          'PENDING_CLASSIFICATION',
          'PENDING_ANALYST_REVIEW',
          'SYSTEM',
          'falta documentación',
          '2026-10-01T12:09:00Z',
        ),
        move(
          'PENDING_ANALYST_REVIEW',
          'AWAITING_DOCUMENTATION',
          'ANALYST',
          'documentación pedida',
          '2026-10-01T16:00:00Z',
        ),
        move(
          'AWAITING_DOCUMENTATION',
          'PENDING_CLASSIFICATION',
          'INSURED',
          'documentación subida',
          '2026-10-04T10:00:00Z',
        ),
        move(
          'PENDING_CLASSIFICATION',
          'PENDING_ANALYST_REVIEW',
          'SYSTEM',
          'clasificación lista',
          '2026-10-04T10:07:00Z',
        ),
      ],
    },
    {
      title: 'Peritaje y reapertura',
      current: 'PENDING_ANALYST_REVIEW',
      history: [
        move(
          null,
          'PENDING_CLASSIFICATION',
          'INSURED',
          'denuncia registrada',
          '2026-09-01T12:00:00Z',
        ),
        move(
          'PENDING_CLASSIFICATION',
          'PENDING_ANALYST_REVIEW',
          'SYSTEM',
          'clasificación lista',
          '2026-09-01T12:10:00Z',
        ),
        move(
          'PENDING_ANALYST_REVIEW',
          'PENDING_EXPERT_REPORT',
          'ANALYST',
          'derivado a peritaje',
          '2026-09-02T09:00:00Z',
        ),
        move(
          'PENDING_EXPERT_REPORT',
          'PENDING_ANALYST_REVIEW',
          'ANALYST',
          'informe recibido',
          '2026-09-12T11:00:00Z',
        ),
        move('PENDING_ANALYST_REVIEW', 'REJECTED', 'ANALYST', 'rechazado', '2026-09-13T14:00:00Z'),
        move('REJECTED', 'PENDING_ANALYST_REVIEW', 'REFERENT', 'reabierto', '2026-09-20T10:00:00Z'),
      ],
    },
  ];

  protected readonly insuredRows = CASE_STATUSES.map((internal) => {
    const view = INSURED_VIEW[internal];
    return {
      internal,
      label: view.label,
      tone: insuredStatusTone(view.code),
      next: insuredNextStep(view.code),
    };
  });

  protected readonly settlementSamples: StateSample[] = [
    {
      code: 'sin fila, o status = null',
      label: 'Propuesta',
      tone: 'neutral',
      implicit: true,
      note: 'Cálculo sin confirmar. No tiene literal en el enum.',
    },
    { code: 'AUTHORIZED', label: 'Autorizada', tone: 'ok' },
    {
      code: 'PENDING_AUTHORIZATION',
      label: 'Esperando al referente',
      tone: 'warning',
      note: 'Supera la atribución del analista en el ramo.',
    },
    {
      code: 'RETURNED',
      label: 'Devuelta por el referente',
      tone: 'risk',
      note: 'Vuelve a propuesta con los valores de hoy.',
    },
  ];

  protected readonly referralSamples: StateSample[] = [
    {
      code: 'notifiedAt = null',
      label: 'Derivado sin aviso por mail',
      tone: 'warning',
      implicit: true,
    },
    {
      code: 'reportReceivedAt = null',
      label: 'Esperando el informe',
      tone: 'info',
      implicit: true,
    },
    { code: 'reportReceivedAt + verdict', label: 'Informe recibido', tone: 'ok', implicit: true },
  ];

  protected readonly userSamples: StateSample[] = [
    { code: 'PENDING', label: userStatusLabel('PENDING'), tone: userStatusTone('PENDING') },
    {
      code: 'PENDING + inviteExpiresAt vencido',
      label: 'Invitación vencida',
      tone: 'risk',
      implicit: true,
      note: 'UserStatus no lo nombra: se ve como Pendiente.',
    },
    { code: 'ACTIVE', label: userStatusLabel('ACTIVE'), tone: userStatusTone('ACTIVE') },
    {
      code: 'ACTIVE + inviteToken',
      label: 'Cambio de contraseña pedido',
      tone: 'info',
      implicit: true,
      note: 'Reusa el token de la invitación.',
    },
    {
      code: 'INACTIVE' satisfies UserStatus,
      label: userStatusLabel('INACTIVE'),
      tone: userStatusTone('INACTIVE'),
      note: 'Ningún flujo lo produce todavía.',
    },
  ];

  protected readonly engineStages = ENGINE_STAGES.map((stage) => ({ stage, ...stageDef(stage) }));

  protected readonly fraudSamples = [
    { inForce: true, scores: true },
    { inForce: true, scores: false },
    { inForce: false, scores: false },
  ] as FraudRecord[];

  protected readonly onboardingSamples: StateSample[] = [
    { code: 'PENDING', label: 'Pendiente', tone: 'warning' },
    { code: 'DONE', label: 'Listo', tone: 'ok' },
    { code: 'SKIPPED', label: 'Descartado', tone: 'neutral' },
    {
      code: 'datos + touchedAt < resetAt',
      label: 'Con datos, sin guardar después de reiniciar',
      tone: 'warning',
      implicit: true,
    },
  ];

  protected readonly saving = signal<boolean | null>(null);
  protected readonly saveFailed = signal(false);

  protected simulateSave(fail: boolean): void {
    this.saveFailed.set(false);
    this.saving.set(true);
    setTimeout(() => {
      this.saveFailed.set(fail);
      this.saving.set(false);
    }, 1200);
  }

  protected internalLabel(status: CaseStatus): string {
    return caseStatusLabel(status);
  }

  protected internalTone(status: CaseStatus): StatusTone {
    return caseStatusTone(status);
  }

  protected fraudLabel(record: FraudRecord): string {
    return fraudRecordStatusLabel(record);
  }

  protected fraudTone(record: FraudRecord): StatusTone {
    return fraudRecordStatusTone(record);
  }
}
