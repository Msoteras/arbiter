import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, combineLatest, map, Observable, of, startWith, switchMap } from 'rxjs';

import { ExpedienteService, AnalystDecisionRequest } from '../expediente.service';
import { DocumentAgendaService } from '../document-agenda.service';
import { CaseNavigationService } from '../case-navigation.service';
import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { UserAdminService } from '../../../core/auth/user-admin.service';
import { ExpedienteResponse, RiskBreakdownItem, StatusTransition } from '../../../core/models/expediente';
import { riskFactorLabel } from '../../../core/models/business-rules';
import { CASE_DOCUMENT_TYPES, CaseDocument, CaseDocumentType } from '../../../core/models/case-document';
import { clasificacionLabel, clasificacionTone } from '../../../core/models/clasificacion';
import {
  estadoLabel,
  estadoSimplificadoLabel,
  estadoTone,
  riskBandEmptyLabel,
} from '../../../core/models/estado';
import { StatusTone } from '../../../core/models/status-tone';
import { formatDateTime } from '../../../core/util/datetime';
import { FraudGaugeComponent } from '../../../shared/ui/fraud-gauge/fraud-gauge.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { StatusTimelineComponent } from '../../../shared/ui/status-timeline/status-timeline.component';
import { ForensicAnalysisComponent } from './forensic-analysis/forensic-analysis.component';
import { CaseDocumentsComponent } from '../case-documents/case-documents.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ModalComponent } from '../../../shared/ui/modal/modal.component';
import { TextareaComponent } from '../../../shared/ui/textarea/textarea.component';
import { MenuButtonComponent, MenuItem } from '../../../shared/ui/menu-button/menu-button.component';

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse }
  | { status: 'error'; httpStatus: number };

type TabId = 'resumen' | 'datos' | 'imagenes' | 'riesgo' | 'documentacion' | 'conversacion' | 'historial';
type Verb = 'aprobar' | 'rechazar';

/** value=null → la sección muestra "Sin datos" (el backend no provee este campo). */
interface FieldItem { label: string; value: string | null; mono?: boolean; full?: boolean; }

@Component({
  selector: 'app-expediente-detail',
  imports: [
    RouterLink,
    FraudGaugeComponent,
    EmptyStateComponent,
    StatusTimelineComponent,
    ForensicAnalysisComponent,
    CaseDocumentsComponent,
    CardComponent,
    ButtonComponent,
    BadgeComponent,
    ModalComponent,
    TextareaComponent,
    MenuButtonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './expediente-detail.component.html',
  styleUrl: './expediente-detail.component.scss',
})
export class ExpedienteDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly service = inject(ExpedienteService);
  private readonly caseNav = inject(CaseNavigationService);
  private readonly session = inject(AuthSessionService);
  private readonly users = inject(UserAdminService);

  /** Bumped after a decision is recorded, to refetch the case and reflect the real backend status. */
  private readonly reloadTrigger = signal(0);

  private readonly state = toSignal(
    combineLatest([
      this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
      toObservable(this.reloadTrigger),
    ]).pipe(
      map(([id]) => id),
      switchMap((id) =>
        this.service.getById(id).pipe(
          map((data): LoadState => ({ status: 'ok', data })),
          startWith<LoadState>({ status: 'loading' }),
          catchError((err: HttpErrorResponse) =>
            of<LoadState>({ status: 'error', httpStatus: err.status }),
          ),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } as LoadState },
  );

  protected readonly loading = computed(() => this.state().status === 'loading');
  protected readonly hasError = computed(() => this.state().status === 'error');

  protected readonly data = computed<ExpedienteResponse | null>(() => {
    const s = this.state();
    return s.status === 'ok' ? s.data : null;
  });

  protected readonly notFound = computed(() => {
    const s = this.state();
    return s.status === 'error' && s.httpStatus === 404;
  });

  // Anterior/siguiente según el orden de la bandeja (no por id correlativo). null cuando no hay
  // vecino (borde de la lista) o cuando se entró por deep-link sin pasar por la bandeja.
  protected readonly prevId = computed<number | null>(() =>
    this.caseNav.neighbor(this.data()?.id, -1),
  );
  protected readonly nextId = computed<number | null>(() =>
    this.caseNav.neighbor(this.data()?.id, 1),
  );

  protected readonly statusLabel = computed(() => {
    const d = this.data();
    return d ? estadoLabel(d.status) : '';
  });

  protected readonly statusTone = computed<StatusTone>(() => {
    const d = this.data();
    return d ? estadoTone(d.status) : 'neutral';
  });

  protected readonly simplifiedStatusLabel = computed(() => {
    const d = this.data();
    return d ? estadoSimplificadoLabel(d.status) : '';
  });

  private static readonly RISK_BAND_GAUGE: Record<string, 1 | 2 | 3 | 4> = {
    LOW: 1,
    MEDIUM: 2,
    HIGH: 3,
    CRITICAL: 4,
  };

  protected readonly riskGaugeBand = computed<1 | 2 | 3 | 4 | null>(() => {
    const band = this.data()?.riskBand;
    return band ? ExpedienteDetailComponent.RISK_BAND_GAUGE[band] : null;
  });

  protected readonly riskGaugeEmptyLabel = computed(() => {
    const d = this.data();
    return d ? riskBandEmptyLabel(d.status, d.analysisClassification) : 'Sin datos';
  });

  /** Score de fraude como porcentaje entero (0..100), o null si no se scoreó. */
  protected readonly riskScorePct = computed<number | null>(() => {
    const score = this.data()?.riskScore;
    return score == null ? null : Math.round(score * 100);
  });

  /** Desglose del score por factor, ordenado por aporte descendente (el que más pesó primero). */
  protected readonly riskBreakdown = computed<RiskBreakdownItem[]>(() => {
    const items = this.data()?.riskBreakdown ?? [];
    return [...items].sort((a, b) => b.weightedContribution - a.weightedContribution);
  });

  /** Suma de los aportes crudos del breakdown; denominador para normalizar cada aporte. */
  private readonly weightedSum = computed<number>(() =>
    this.riskBreakdown().reduce((sum, item) => sum + item.weightedContribution, 0),
  );

  /**
   * Alcance del score en un Fast Track. En el carril rápido el análisis pesado (documentación +
   * imágenes) corre solo si la aseguradora lo activó, así que el score puede ser parcial: solo con
   * factores de datos duros. Detecta si corrió por la presencia del análisis forense o de algún
   * factor pesado en el desglose. Devuelve null si no es Fast Track o no hay score (no aplica aviso).
   */
  protected readonly fastTrackScoreScope = computed<'partial' | 'full' | null>(() => {
    const d = this.data();
    if (!d || d.analysisClassification !== 'FAST_TRACK' || d.riskScore == null) {
      return null;
    }
    const heavyFactors = new Set(['image_reuse', 'image_web_match', 'document_inconsistency']);
    const ranHeavy =
      !!d.forensicReport || (d.riskBreakdown ?? []).some((i) => heavyFactors.has(i.factorId));
    return ranHeavy ? 'full' : 'partial';
  });

  protected factorLabel(factorId: string): string {
    return riskFactorLabel(factorId);
  }

  protected pct(value: number): number {
    return Math.round(value * 100);
  }

  /**
   * Aporte de un factor AL score, en la misma escala 0–100 que el score que ve el analista. El
   * backend guarda el aporte crudo (rawScore × peso) sin normalizar, pero el score final se divide
   * por la suma de pesos; sin re-normalizar acá, los aportes por factor no sumarían el score de
   * arriba. Con esto sí: la columna suma exactamente el score. No se muestra el peso crudo — es
   * config relativa del referente y al analista solo le importa cuánto empujó cada factor ESTE score.
   */
  protected aporteAlScore(item: RiskBreakdownItem): number {
    const total = this.weightedSum();
    const score = this.data()?.riskScore ?? 0;
    return total === 0 ? 0 : Math.round((item.weightedContribution / total) * score * 100);
  }

  protected readonly classificationLabel = computed(() => {
    const d = this.data();
    return d ? clasificacionLabel(d.analysisClassification) : '';
  });

  protected readonly classificationTone = computed<StatusTone>(() => {
    const d = this.data();
    return d ? clasificacionTone(d.analysisClassification) : 'neutral';
  });

  protected readonly confidencePercent = computed(() => {
    const d = this.data();
    return d ? Math.round(d.analysisConfidence * 100) : 0;
  });

  /** Grilla del expediente: real donde el backend lo da, null ("Sin datos") en el resto. */
  protected readonly fields = computed<FieldItem[]>(() => {
    const d = this.data();
    return [
      { label: 'N° de siniestro / denuncia', value: d ? `#${d.id}` : null, mono: true },
      { label: 'N° de póliza', value: d?.policyNumber ?? null, mono: true },
      { label: 'Rama', value: d?.branch ?? null },
      { label: 'Producto', value: d?.product ?? null },
      { label: 'Asegurado', value: d?.insuredName ?? null },
      { label: 'DNI', value: d?.insuredId ?? null, mono: true },
      // Declaración UIF/PLA del propio asegurado, junto al resto de sus datos: es donde el analista
      // la busca. "No" es un valor, no la ausencia de dato, así que no cae en el `?? null` que el
      // resto de las filas usa para mostrar "Sin datos" (D16).
      { label: 'PEP (declarativo)', value: d ? (d.pep ? 'Sí' : 'No') : null },
      { label: 'Bien asegurado', value: d?.insuredItem ?? null },
      { label: 'Importe reclamado', value: d?.claimedAmount ? `$${d.claimedAmount.toLocaleString()}` : null },
      { label: 'Fecha de denuncia', value: d?.createdAt ? formatDateTime(d.createdAt) : null },
      { label: 'Fecha y hora de ocurrencia', value: d?.eventDate ? formatDateTime(d.eventDate) : null },
      { label: 'Causa', value: d?.claimCause ?? null },
      { label: 'Ubicación', value: d?.eventLocation ?? null, full: true },
      { label: 'Descripción', value: d?.description ?? null, full: true },
      { label: 'Analista asignado', value: d?.assignedAnalystName ?? null },
    ];
  });

  // ----- historial de estados (GET /{id} lo trae con timestamps de cada transición) -----
  protected readonly history = computed<StatusTransition[]>(() => this.data()?.statusHistory ?? []);

  // ----- tabs -----
  protected readonly tabs: { id: TabId; label: string }[] = [
    { id: 'resumen', label: 'Resumen' },
    { id: 'datos', label: 'Datos extraídos' },
    { id: 'imagenes', label: 'Análisis de imágenes' },
    { id: 'riesgo', label: 'Desglose de riesgo' },
    { id: 'documentacion', label: 'Documentación' },
    { id: 'conversacion', label: 'Conversación' },
    { id: 'historial', label: 'Historial' },
  ];
  protected readonly activeTab = signal<TabId>('resumen');
  setTab(t: TabId): void {
    this.activeTab.set(t);
  }

  // La "aceptación/modificación" local de la recomendación se quitó: no persistía ni auditaba nada
  // (aparentaba una acción que no ocurría). La única decisión real del analista es Aprobar/Rechazar,
  // que sí persiste vía POST /cases/{id}/decision (abajo).

  // ----- decisión del analista (persiste vía POST /cases/{id}/decision) -----
  private readonly verbLabels: Record<Verb, string> = {
    aprobar: 'Aprobar',
    rechazar: 'Rechazar',
  };

  /** Fuente de verdad: el estado del expediente en el backend, no un flag local. */
  protected readonly decisionState = computed<'pending' | 'approved' | 'rejected' | 'not-ready'>(() => {
    switch (this.data()?.status) {
      case 'APPROVED': return 'approved';
      case 'REJECTED': return 'rejected';
      case 'PENDING_ANALYST_REVIEW': return 'pending';
      default: return 'not-ready';
    }
  });

  protected readonly pendingDecision = signal<Verb | null>(null);
  protected readonly showJustify = signal(false);
  protected readonly justification = signal('');
  protected readonly decisionError = signal<string | null>(null);
  protected readonly decisionSaving = signal(false);

  verbLabel(v: Verb | null): string {
    return v ? this.verbLabels[v] : '';
  }

  askDecision(v: Verb): void {
    this.pendingDecision.set(v);
    this.justification.set('');
    this.decisionError.set(null);
    this.showJustify.set(true);
  }
  cancelDecision(): void {
    this.showJustify.set(false);
    this.pendingDecision.set(null);
  }
  confirmDecision(): void {
    const verb = this.pendingDecision();
    if (!this.justification().trim() || !verb) {
      return;
    }

    const d = this.data();
    if (!d) {
      return;
    }

    const decisionPayload: AnalystDecisionRequest = {
      decision: verb === 'aprobar' ? 'APPROVE' : 'REJECT',
      justification: this.justification().trim(),
    };

    this.decisionSaving.set(true);
    this.decisionError.set(null);

    this.service.recordAnalystDecision(d.id, decisionPayload).subscribe({
      next: () => {
        this.showJustify.set(false);
        this.pendingDecision.set(null);
        this.decisionSaving.set(false);
        this.reloadTrigger.update((v) => v + 1);
      },
      error: (err: HttpErrorResponse) => {
        this.decisionSaving.set(false);
        this.decisionError.set(err.error?.detail || 'No se pudo registrar la decisión');
      },
    });
  }

  // ----- reintento manual de la clasificación (expediente en CLASSIFICATION_FAILED) -----
  // El scheduler solo barre PENDING_CLASSIFICATION, así que un caso que agotó los reintentos queda
  // varado hasta que el analista lo reencola a mano (bugs-ux #22). No resuelve el caso: lo devuelve
  // al pipeline, que después vuelve a necesitar la decisión del analista.
  protected readonly isFailed = computed(() => this.data()?.status === 'CLASSIFICATION_FAILED');
  protected readonly retrying = signal(false);
  protected readonly retryError = signal<string | null>(null);

  retryClassification(): void {
    const d = this.data();
    if (!d || this.retrying()) {
      return;
    }
    this.retrying.set(true);
    this.retryError.set(null);
    this.service.retryClassification(d.id).subscribe({
      next: () => {
        this.retrying.set(false);
        this.reloadTrigger.update((v) => v + 1);
      },
      error: (err: HttpErrorResponse) => {
        this.retrying.set(false);
        this.retryError.set(err.error?.detail || 'No se pudo reintentar la clasificación');
      },
    });
  }

  // ----- asignación del expediente -----
  // Asignar es poner dueño, no resolver: no mueve el expediente de estado ni reemplaza la
  // decisión del analista (human-in-the-loop, decisión de arquitectura #5). Los dos roles
  // operativos pueden asignar; solo el analista puede tomarlo para sí.
  protected readonly assignSaving = signal(false);
  protected readonly assignError = signal<string | null>(null);

  private readonly analysts = toSignal(this.users.listAnalysts().pipe(catchError(() => of([]))), {
    initialValue: [],
  });

  protected readonly analystMenuItems = computed<MenuItem[]>(() => {
    // "Asignar a otro analista": el que ya lo tiene no va en la lista (reasignárselo no es acción).
    const assignedId = this.data()?.assignedAnalystId;
    return this.analysts()
      .filter((a) => a.id !== assignedId)
      .map((a) => ({ value: String(a.id), label: `${a.nombre} ${a.apellido}` }));
  });

  /**
   * Mi id de analista DENTRO de esta aseguradora — no el de usuario de la sesión, que es otra
   * tabla. Sale de buscarme por email en el listado de analistas, que ya viene acotado al tenant.
   * Null para el referente, que no tiene perfil de analista.
   */
  private readonly myAnalystId = computed<number | null>(() => {
    const email = this.session.session()?.email;
    return this.analysts().find((a) => a.email === email)?.id ?? null;
  });

  protected readonly canTake = computed(
    () => this.session.session()?.rol === 'ANALISTA_SINIESTROS' && this.myAnalystId() != null,
  );

  /**
   * Solo el analista opera sobre el expediente (asignar, decidir, aceptar/modificar la
   * clasificación, reintentar). El referente lo ve de solo lectura.
   */
  protected readonly canAct = computed(
    () => this.session.session()?.rol === 'ANALISTA_SINIESTROS',
  );

  protected readonly assignedName = computed(() => this.data()?.assignedAnalystName ?? null);
  protected readonly isAssigned = computed(() => this.data()?.assignedAnalystId != null);

  /** True cuando el expediente está asignado al analista logueado (para el "Vos"). */
  protected readonly isMine = computed(() => {
    const id = this.data()?.assignedAnalystId;
    return id != null && id === this.myAnalystId();
  });

  /** Iniciales del analista asignado para el avatar (hasta 2). */
  protected readonly analystInitials = computed(() =>
    (this.assignedName() ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]!.toUpperCase())
      .join(''),
  );

  /**
   * Fecha de la asignación vigente, sacada del historial: la asignación deja un hito con
   * fromStatus == toStatus y actor ANALYST (ver CaseStatusService.recordAssignment). El último de
   * esos hitos, estando asignado, es la asignación actual.
   */
  private readonly assignedSince = computed<string | null>(() => {
    const milestones = (this.data()?.statusHistory ?? []).filter(
      (t) => t.fromStatus === t.toStatus && t.actor === 'ANALYST',
    );
    const last = milestones.at(-1);
    return last
      ? new Date(last.changedAt).toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit' })
      : null;
  });

  /** Subtítulo del avatar: "Vos · desde el 04/06" (cada parte según disponibilidad). */
  protected readonly assignedContext = computed(() => {
    const parts: string[] = [];
    if (this.isMine()) parts.push('Vos');
    const since = this.assignedSince();
    if (since) parts.push(`desde el ${since}`);
    return parts.join(' · ');
  });

  /** Menú "…" del recuadro de asignación: por ahora solo la acción destructiva de liberar. */
  protected readonly overflowMenuItems: MenuItem[] = [
    { value: 'release', label: 'Liberar', danger: true },
  ];

  protected onOverflowMenu(value: string): void {
    if (value === 'release') this.release();
  }

  protected take(): void {
    const me = this.myAnalystId();
    const d = this.data();
    if (me != null && d) {
      this.runAssignment(this.service.assign(d.id, me));
    }
  }

  protected assignTo(analystId: string): void {
    const d = this.data();
    if (d) {
      this.runAssignment(this.service.assign(d.id, Number(analystId)));
    }
  }

  protected release(): void {
    const d = this.data();
    if (d) {
      this.runAssignment(this.service.unassign(d.id));
    }
  }

  private runAssignment(request: Observable<ExpedienteResponse>): void {
    this.assignSaving.set(true);
    this.assignError.set(null);
    request.subscribe({
      next: () => {
        this.assignSaving.set(false);
        // Releer: la asignación también deja un hito nuevo en el historial de transiciones.
        this.reloadTrigger.update((v) => v + 1);
      },
      error: (err: HttpErrorResponse) => {
        this.assignSaving.set(false);
        this.assignError.set(err.error?.detail || 'No se pudo actualizar la asignación');
      },
    });
  }

  // ----- documentación faltante (FALTA_DOCUMENTACION), SOLO LECTURA para el analista -----
  // El analista no sube documentos: la carga es exclusiva del asegurado desde su portal. Acá
  // solo se le listan los tipos requeridos que todavía no se cargaron.
  protected readonly needsDocs = computed(() =>
    this.data()?.analysisClassification === 'FALTA_DOCUMENTACION'
  );

  /** La agenda documental es otra llamada al backend: se refresca con el mismo trigger. */
  protected readonly docsReloadToken = computed(() => this.reloadTrigger());

  private readonly documents = toSignal(
    combineLatest([
      this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
      toObservable(this.reloadTrigger),
    ]).pipe(
      switchMap(([id]) =>
        id
          ? this.service.listDocuments(Number(id)).pipe(catchError(() => of<CaseDocument[]>([])))
          : of<CaseDocument[]>([]),
      ),
    ),
    { initialValue: [] as CaseDocument[] },
  );

  private readonly agenda = inject(DocumentAgendaService);

  /** La agenda REAL del ramo del expediente (la que configuró el referente), o el catálogo completo. */
  private readonly requiredDocTypes = toSignal(
    toObservable(computed(() => this.data()?.branch ?? null)).pipe(
      switchMap((branch) => (branch ? this.agenda.slotsForBranch(branch) : of(CASE_DOCUMENT_TYPES))),
    ),
    { initialValue: CASE_DOCUMENT_TYPES as readonly CaseDocumentType[] },
  );

  /** Tipos requeridos que todavía no se cargaron — lo que el analista ve como "falta". */
  protected readonly missingDocLabels = computed(() => {
    const present = new Set(this.documents().map((d) => d.type));
    return this.requiredDocTypes().filter((t) => !present.has(t.type)).map((t) => t.label);
  });
}
