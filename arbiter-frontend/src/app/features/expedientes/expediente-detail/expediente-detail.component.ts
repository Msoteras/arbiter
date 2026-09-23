import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import {
  catchError,
  combineLatest,
  finalize,
  map,
  Observable,
  of,
  startWith,
  switchMap,
} from 'rxjs';

import { ExpedienteService, AnalystDecisionRequest, Settlement } from '../expediente.service';
import { DocumentAgendaService } from '../document-agenda.service';
import { CaseNavigationService } from '../case-navigation.service';
import { CaseMessagesService } from '../case-messages.service';
import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { UserAdminService } from '../../../core/auth/user-admin.service';
import {
  DocumentAnalysis,
  ExpedienteResponse,
  RiskBreakdownItem,
  StatusTransition,
} from '../../../core/models/expediente';
import { conLabelesDeDocumento, riskFactorLabel } from '../../../core/models/business-rules';
import { Policy } from '../../../core/models/policy';
import {
  PolicySnapshot,
  RuleResult,
  advisoryResultLabel,
  advisoryResultTone,
  isAdvisoryCheck,
  isFastTrackCriterion,
  ruleEvaluationText,
  ruleResultLabel,
  ruleResultTone,
  ruleTypeLabel,
} from '../../../core/models/trazabilidad';
import {
  CASE_DOCUMENT_TYPES,
  CaseDocument,
  CaseDocumentType,
  documentTypeLabel,
} from '../../../core/models/case-document';
import { clasificacionLabel, clasificacionTone } from '../../../core/models/clasificacion';
import { forensicAlertLevel } from '../../../core/models/forensic';
import {
  causeConsistencyLabel,
  causeConsistencyTone,
  shouldSurfaceCauseConsistency,
} from '../../../core/models/cause-consistency';
import {
  ExpertVerdict,
  OpcionesDerivacion,
  Peritaje,
  ProviderType,
  REPAIR_OUTCOME_OPTIONS,
  RepairOutcome,
  repairOutcomeLabel,
  veredictoLabel,
  veredictoTone,
} from '../../../core/models/peritaje';
import {
  AntecedenteFraude,
  MOTIVO_ANTECEDENTE_MIN,
  OrigenAntecedente,
  efectoAntecedente,
  estadoAntecedenteLabel,
  estadoAntecedenteTone,
  origenAntecedenteLabel,
} from '../../../core/models/antecedente-fraude';
import {
  estadoLabel,
  estadoSimplificadoLabel,
  estadoTone,
  isEstadoFinal,
  riskBandEmptyLabel,
} from '../../../core/models/estado';
import {
  deadlinePriorityLabel,
  deadlinePriorityTone,
  isDeadlinePrioritized,
} from '../../../core/models/deadline-priority';
import { RiskBand, riskBandLabel } from '../../../core/models/risk-band';
import { StatusTone } from '../../../core/models/status-tone';
import { formatDate, formatDateTime } from '../../../core/util/datetime';
import { FraudGaugeComponent } from '../../../shared/ui/fraud-gauge/fraud-gauge.component';
import { InfoTipComponent } from '../../../shared/ui/info-tip/info-tip.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { StatusTimelineComponent } from '../../../shared/ui/status-timeline/status-timeline.component';
import { ForensicAnalysisComponent } from './forensic-analysis/forensic-analysis.component';
import { CaseDocumentsComponent } from '../case-documents/case-documents.component';
import { CaseChatComponent } from '../case-chat/case-chat.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ModalComponent } from '../../../shared/ui/modal/modal.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { TextareaComponent } from '../../../shared/ui/textarea/textarea.component';
import {
  MenuButtonComponent,
  MenuItem,
} from '../../../shared/ui/menu-button/menu-button.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { fadeInUp, staggerReveal, tabSwitch } from '../../../shared/animations';

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse }
  | { status: 'error'; httpStatus: number };

type DocsState = { status: 'loading' } | { status: 'ok'; list: CaseDocument[] };

type TabId =
  | 'resumen'
  | 'analisis'
  | 'reglas'
  | 'documentacion'
  | 'imagenes'
  | 'asegurado'
  | 'peritaje'
  | 'conversacion'
  | 'historial';
type Verb = 'aprobar' | 'rechazar';

/** null renders as "Sin datos": the backend doesn't provide it. */
interface FieldItem {
  label: string;
  value: string | null;
  mono?: boolean;
  full?: boolean;
  sub?: string;
}

/** Hours for the first couple of days, days afterwards. */
function demoraDenuncia(eventDate: string, createdAt: string): string | undefined {
  const horas = Math.round((Date.parse(createdAt) - Date.parse(eventDate)) / 3_600_000);
  if (Number.isNaN(horas) || horas < 0) {
    return undefined;
  }
  if (horas < 48) {
    return horas === 1 ? '1 h después del hecho' : `${horas} h después del hecho`;
  }
  return `${Math.round(horas / 24)} días después del hecho`;
}

/** A signal worth reading before deciding, and the tab where its evidence lives. */
interface BriefAlert {
  label: string;
  tab: TabId;
}

@Component({
  selector: 'app-expediente-detail',
  imports: [
    RouterLink,
    FraudGaugeComponent,
    InfoTipComponent,
    EmptyStateComponent,
    StatusTimelineComponent,
    ForensicAnalysisComponent,
    CaseDocumentsComponent,
    CaseChatComponent,
    CardComponent,
    ButtonComponent,
    BadgeComponent,
    ModalComponent,
    SelectComponent,
    InputComponent,
    TextareaComponent,
    MenuButtonComponent,
    InlineLoadingComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [fadeInUp, staggerReveal, tabSwitch],
  templateUrl: './expediente-detail.component.html',
  // Order matters: both files are concatenated in this order and the cascade depends on it.
  styleUrls: [
    './expediente-detail.component.scss',
    './expediente-detail-paneles.scss',
    './expediente-detail-analisis.scss',
  ],
})
export class ExpedienteDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly service = inject(ExpedienteService);
  private readonly caseNav = inject(CaseNavigationService);
  private readonly session = inject(AuthSessionService);
  private readonly users = inject(UserAdminService);
  private readonly messages = inject(CaseMessagesService);

  constructor() {
    // The dot has to be there before the analyst opens the tab, so the count is fetched with the
    // case and not when the thread mounts. Keyed on the id and not on data(), which comes back as a
    // new object on every refetch and asked for the thread twice per open.
    effect(() => {
      const id = this.loadedCaseId();
      untracked(() => {
        this.unreadMessages.set(0);
        if (id) {
          this.messages.thread(id).subscribe({
            next: (thread) => this.unreadMessages.set(thread.unread),
            error: () => undefined,
          });
        }
      });
    });
  }

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

  /** The loaded case's id, which unlike {@link data} only changes when the case actually does. */
  private readonly loadedCaseId = computed<number | null>(() => this.data()?.id ?? null);

  // Previous/next in inbox order; null at the edges or on a deep link.
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

  protected readonly riskScorePct = computed<number | null>(() => {
    const score = this.data()?.riskScore;
    return score == null ? null : Math.round(score * 100);
  });

  protected readonly riskBreakdown = computed<RiskBreakdownItem[]>(() => {
    const items = this.data()?.riskBreakdown ?? [];
    return [...items].sort((a, b) => b.weightedContribution - a.weightedContribution);
  });

  private readonly weightedSum = computed<number>(() =>
    this.riskBreakdown().reduce((sum, item) => sum + item.weightedContribution, 0),
  );

  /**
   * In Fast Track the heavy analysis (documents + images) only runs if the insurer enabled it,
   * so the score may be partial. Null when not Fast Track or not scored.
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
   * Normalized to the displayed 0–100 scale (the backend stores rawScore × weight unnormalized),
   * so the column adds up exactly to the score.
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

  /**
   * Only shown when there is something to look at: MATCHES adds nothing and null means the check
   * didn't run.
   */
  protected readonly showCauseConsistency = computed(() =>
    shouldSurfaceCauseConsistency(this.data()?.causeConsistency),
  );

  protected readonly causeConsistencyLabel = computed(() => {
    const value = this.data()?.causeConsistency;
    return value ? causeConsistencyLabel(value) : '';
  });

  protected readonly causeConsistencyTone = computed<StatusTone>(() => {
    const value = this.data()?.causeConsistency;
    return value ? causeConsistencyTone(value) : 'neutral';
  });

  protected readonly confidencePercent = computed(() => {
    const d = this.data();
    return d ? Math.round(d.analysisConfidence * 100) : 0;
  });

  /**
   * Empty for Fast Track / missing documentation (rules gate, not the LLM) or before
   * classification; the tab is hidden then.
   */
  protected readonly analysisReasons = computed<string[]>(() =>
    (this.data()?.analysisReasons ?? []).map(conLabelesDeDocumento),
  );

  /** Empty until classified, or when no document was read. */
  protected readonly documentAnalyses = computed<DocumentAnalysis[]>(
    () => this.data()?.documentAnalyses ?? [],
  );

  protected documentLabel(type: string): string {
    return documentTypeLabel(type);
  }

  /**
   * FAST_TRACK and FALTA_DOCUMENTACION come from the rules gate, not the LLM, so no model confidence
   * is shown for them: the backend's 100% is a fixed value, not a measurement.
   */
  protected readonly isDeterministicOutcome = computed(() => {
    const c = this.data()?.analysisClassification;
    return c === 'FAST_TRACK' || c === 'FALTA_DOCUMENTACION';
  });

  protected readonly resumenGroups = computed<{ heading: string; fields: FieldItem[] }[]>(() => {
    const d = this.data();
    const sumInsured = this.policySnapshot()?.sumInsured;
    return [
      {
        heading: 'Siniestro',
        fields: [
          { label: 'Causa', value: d?.claimCause ?? null },
          {
            label: 'Fecha y hora de ocurrencia',
            value: d?.eventDate ? formatDateTime(d.eventDate) : null,
          },
          { label: 'Ubicación', value: d?.eventLocation ?? null },
        ],
      },
      {
        heading: 'Bien asegurado',
        fields: [
          { label: 'Bien asegurado', value: d?.insuredItem ?? null },
          {
            label: 'Importe reclamado',
            value: d?.claimedAmount ? this.formatMonto(d.claimedAmount) : null,
            sub:
              d?.claimedAmount && sumInsured
                ? `${Math.round((d.claimedAmount / sumInsured) * 100)}% de la suma asegurada`
                : undefined,
          },
          {
            label: 'Fecha de denuncia',
            value: d?.createdAt ? formatDateTime(d.createdAt) : null,
            sub: d?.createdAt && d.eventDate ? demoraDenuncia(d.eventDate, d.createdAt) : undefined,
          },
        ],
      },
      {
        heading: 'Asegurado y póliza',
        fields: [
          { label: 'Asegurado', value: d?.insuredName ?? null },
          { label: 'DNI', value: d?.insuredId ?? null, mono: true },
          // "No" is a value, not missing data, so it skips the `?? null` "Sin datos" path.
          { label: 'PEP (declarativo)', value: d ? (d.pep ? 'Sí' : 'No') : null },
          { label: 'N° de póliza', value: d?.policyNumber ?? null, mono: true },
          { label: 'Producto', value: d?.product ?? null },
          { label: 'Rama', value: d?.branch ?? null },
        ],
      },
    ];
  });

  // ----- traceability -----
  private readonly ruleResults = computed<RuleResult[]>(() => this.data()?.ruleResults ?? []);

  /** Kept apart from the Fast Track criteria: failing one means something different in each table. */
  protected readonly hardRuleResults = computed<RuleResult[]>(() =>
    this.ruleResults().filter(
      (r) => !isFastTrackCriterion(r.ruleType) && !isAdvisoryCheck(r.ruleType),
    ),
  );

  /**
   * Los avisos: no deciden cobertura ni carril rápido, marcan algo para mirar antes de resolver
   * (hoy, que la documentación narre otro hecho que el declarado). Van aparte y arriba de las
   * reglas porque un "No cumple" entre ellas se leería como una exclusión que el motor no dictó.
   */
  protected readonly advisoryChecks = computed<RuleResult[]>(() =>
    this.ruleResults().filter((r) => isAdvisoryCheck(r.ruleType)),
  );

  protected readonly hasAdvisoryWarning = computed(() =>
    this.advisoryChecks().some((r) => r.result === 'FAIL'),
  );

  /** Present both when the case took Fast Track (why) and when it didn't (which criterion failed). */
  protected readonly fastTrackCriteria = computed<RuleResult[]>(() =>
    this.ruleResults().filter((r) => isFastTrackCriterion(r.ruleType)),
  );

  protected readonly esFastTrack = computed(
    () => this.data()?.analysisClassification === 'FAST_TRACK',
  );

  /** The load failed, as opposed to "no rules ran", which is a fact about the case. */
  protected readonly ruleResultsUnavailable = computed(() => this.data()?.ruleResults === null);

  /**
   * Never Fast Track: the gate runs after the hard rules. Empty when the insurer has no active rules
   * or the claim stopped at the mandatory-documents check, which returns before the other evaluators.
   */
  protected readonly sinReglasMotivo = computed(() => {
    if (this.ruleResultsUnavailable()) {
      return 'No se pudieron leer las reglas evaluadas. Volvé a intentar en unos minutos.';
    }
    if (this.needsDocs()) {
      return (
        'Todavía no se evaluaron: el expediente está esperando documentación obligatoria. ' +
        'Se evalúan cuando se complete y vuelva a clasificarse.'
      );
    }
    return 'No hay reglas duras activas para esta cobertura.';
  });

  /** Hidden when there is neither a score nor model reasons (e.g. Fast Track without score). */
  protected readonly hayAnalisis = computed(
    () => this.data()?.riskScore != null || this.analysisReasons().length > 0,
  );

  /** Hidden before classification: "no active rules" would be false, they just haven't run yet. */
  protected readonly hayReglas = computed(
    () =>
      this.ruleResults().length > 0 ||
      this.ruleResultsUnavailable() ||
      this.policySnapshot() != null ||
      !!this.data()?.analysisClassification,
  );

  protected readonly reglasFallidas = computed(
    () => this.hardRuleResults().filter((r) => r.result === 'FAIL').length,
  );

  protected readonly reglasCumplidas = computed(
    () => this.hardRuleResults().filter((r) => r.result === 'PASS').length,
  );

  protected readonly scoreSegments = computed(() =>
    this.riskBreakdown()
      .map((item) => ({ factorId: item.factorId, aporte: this.aporteAlScore(item) }))
      .filter((s) => s.aporte > 0),
  );

  protected readonly scoreTone = computed<StatusTone>(() => {
    const tones: Record<number, StatusTone> = { 1: 'ok', 2: 'warning', 3: 'risk', 4: 'danger' };
    const band = this.riskGaugeBand();
    return band ? tones[band] : 'neutral';
  });

  protected readonly policySnapshot = computed<PolicySnapshot | null>(
    () => this.data()?.policySnapshot ?? null,
  );

  // ----- insured's policies (current data, not the classification snapshot) -----
  // Lazy-loaded from their own endpoint: an insurer-DB query most case views never need.
  private readonly polizas = toSignal(
    combineLatest([
      this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
      toObservable(this.reloadTrigger),
    ]).pipe(
      switchMap(([id]) =>
        this.service
          .polizasDelAsegurado(id as unknown as number)
          .pipe(catchError(() => of<Policy[]>([]))),
      ),
    ),
    { initialValue: [] as Policy[] },
  );

  /** All of them, the claim's own policy included. */
  protected readonly polizasDelAsegurado = computed<Policy[]>(() => this.polizas());

  /**
   * Only current policies are listed, so the claim's policy is missing if it expired after the
   * claim; the title is omitted then.
   */
  protected readonly gruposDePolizas = computed<{ heading: string; polizas: Policy[] }[]>(() => {
    const numero = this.data()?.policyNumber;
    const todas = this.polizasDelAsegurado();
    const propia = todas.filter((p) => p.policyNumber === numero);
    const otras = todas.filter((p) => p.policyNumber !== numero);
    return [
      ...(propia.length > 0 ? [{ heading: 'Póliza del expediente', polizas: propia }] : []),
      ...(otras.length > 0
        ? [{ heading: propia.length > 0 ? 'Sus otras pólizas' : 'Sus pólizas', polizas: otras }]
        : []),
    ];
  });

  private readonly polizaAbierta = signal<string | null>(null);

  protected estaAbierta(p: Policy): boolean {
    return this.polizaAbierta() === p.policyNumber;
  }

  protected togglePoliza(p: Policy): void {
    this.polizaAbierta.update((abierta) => (abierta === p.policyNumber ? null : p.policyNumber));
  }

  /**
   * Two blocks: the last two fields span all of the insured's policies, so they must not read as
   * something to subtract from this sum insured.
   */
  protected readonly snapshotGroups = computed<{ heading: string | null; fields: FieldItem[] }[]>(
    () => {
      const s = this.policySnapshot();
      return [
        {
          heading: 'Póliza',
          fields: [
            { label: 'N° de póliza', value: s?.externalPolicyNumber ?? null, mono: true },
            { label: 'Cobertura', value: this.data()?.coverage ?? null },
            { label: 'Suma asegurada', value: s ? this.formatMonto(s.sumInsured) : null },
            {
              label: 'Vigencia al momento del hecho',
              value: s ? (s.inForce ? 'Vigente' : 'No vigente') : null,
            },
            {
              label: 'Estado de pago',
              value: s ? (s.paymentsUpToDate ? 'Al día' : 'En mora') : null,
            },
          ],
        },
        {
          heading: 'Historial',
          fields: [
            { label: 'Siniestros previos', value: s ? String(s.previousClaims) : null },
            {
              // Holds monto_indemnizado (what was paid, not what was claimed) despite the field name.
              label: 'Total indemnizado',
              value: s?.totalAmountClaimed != null ? this.formatMonto(s.totalAmountClaimed) : null,
            },
          ],
        },
      ];
    },
  );

  /** Policies count even though they arrive later, from their own request. */
  protected readonly hayDatosAsegurado = computed(
    () => this.polizasDelAsegurado().length > 0 || this.antecedentes().length > 0,
  );

  protected vigencia(p: Policy): string {
    return `${formatDate(p.effectiveFrom)} — ${formatDate(p.effectiveTo)}`;
  }

  protected monto(value: number | null): string | null {
    return value == null ? null : this.formatMonto(value);
  }

  // ----- status history -----
  protected readonly history = computed<StatusTransition[]>(() => this.data()?.statusHistory ?? []);

  /** Only when forensic analysis actually ran on some image. */
  protected readonly hayAnalisisImagenes = computed(
    () => (this.data()?.forensicReport?.findings?.length ?? 0) > 0,
  );

  /** Medium or high findings only; flagging "low" ones would turn the dot into noise. */
  private readonly hayCoincidenciasImagen = computed(() =>
    (this.data()?.forensicReport?.findings ?? []).some((f) => {
      const level = forensicAlertLevel(f);
      return level === 'medio' || level === 'alto';
    }),
  );

  // ----- tabs -----
  // Conditional tabs appear only once something has run (referral, classification, forensics).
  // 'conversacion' is always shown: an empty thread is where talking to the insured starts. It
  // carries a dot when something is unread.
  protected readonly tabs = computed<
    {
      id: TabId;
      label: string;
      dot?: boolean;
      dotLabel?: string;
      count?: string;
    }[]
  >(() => [
    { id: 'resumen' as TabId, label: 'Resumen' },
    ...(this.hayAnalisis()
      ? [
          {
            id: 'analisis' as TabId,
            label: 'Análisis',
            count:
              this.analysisReasons().length > 0 ? `${this.analysisReasons().length}` : undefined,
          },
        ]
      : []),
    ...(this.hayReglas() ? [{ id: 'reglas' as TabId, label: 'Evaluación de reglas' }] : []),
    { id: 'documentacion' as TabId, label: 'Documentación' },
    ...(this.hayAnalisisImagenes()
      ? [
          {
            id: 'imagenes' as TabId,
            label: 'Imágenes',
            dot: this.hayCoincidenciasImagen(),
            dotLabel: 'con coincidencias de imagen',
          },
        ]
      : []),
    ...(this.hayDatosAsegurado() ? [{ id: 'asegurado' as TabId, label: 'Asegurado' }] : []),
    ...(this.derivaciones().length > 0
      ? [{ id: 'peritaje' as TabId, label: this.derivacionesTabLabel() }]
      : []),
    {
      id: 'conversacion' as TabId,
      label: 'Conversación',
      dot: this.unreadMessages() > 0,
      dotLabel: 'con mensajes sin leer',
    },
    { id: 'historial' as TabId, label: 'Historial' },
  ]);

  /**
   * Unread messages from the insured. Fetched apart from the case and not as one more
   * `CaseResponse` field: the same DTO builds the inbox, so counting per row would be one query
   * per listed case.
   */
  protected readonly unreadMessages = signal(0);
  private readonly selectedTab = signal<TabId>('resumen');

  /**
   * Falls back when the selected tab disappears with the data (e.g. a reclassification drops the
   * image analysis).
   */
  protected readonly activeTab = computed<TabId>(() => {
    const selected = this.selectedTab();
    return this.tabs().some((t) => t.id === selected) ? selected : 'resumen';
  });

  setTab(t: TabId): void {
    this.selectedTab.set(t);
  }

  // ----- analyst decision -----
  private readonly verbLabels: Record<Verb, string> = {
    aprobar: 'Aprobar',
    rechazar: 'Rechazar',
  };

  /** Derived from the backend status, not a local flag. */
  protected readonly decisionState = computed<
    'pending' | 'approved' | 'rejected' | 'lapsed' | 'not-ready'
  >(() => {
    switch (this.data()?.status) {
      case 'APPROVED':
        return 'approved';
      case 'REJECTED':
        return 'rejected';
      // Terminal without a decision: closed by the system after the insured's inactivity.
      case 'LAPSED':
        return 'lapsed';
      case 'PENDING_ANALYST_REVIEW':
        return 'pending';
      default:
        return 'not-ready';
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

  protected readonly decisionModalHeading = computed(() =>
    this.pendingDecision() === 'aprobar'
      ? 'Aprobar y determinar el monto a pagar'
      : 'Justificar decisión: Rechazar',
  );

  protected readonly confirmDisabled = computed(
    () =>
      this.decisionSaving() ||
      !this.justification().trim() ||
      (this.pendingDecision() === 'aprobar' && this.approvalBlockedReason() !== null),
  );

  askDecision(v: Verb): void {
    this.pendingDecision.set(v);
    this.justification.set('');
    this.decisionError.set(null);
    // Reset the settlement draft so a cancelled approval doesn't reappear.
    this.replacementInput.set('');
    this.replacementApplied.set(null);
    this.settledAmountInput.set('');
    this.adjustmentReason.set('');
    this.showJustify.set(true);
  }
  cancelDecision(): void {
    this.showJustify.set(false);
    this.pendingDecision.set(null);
  }
  confirmDecision(): void {
    const verb = this.pendingDecision();
    if (!verb) {
      return;
    }
    // Same condition as the button, so Enter can't submit what the click wouldn't.
    if (this.confirmDisabled()) {
      return;
    }

    const d = this.data();
    if (!d) {
      return;
    }

    const decisionPayload: AnalystDecisionRequest = {
      decision: verb === 'aprobar' ? 'APPROVE' : 'REJECT',
      justification: this.justification().trim(),
      // The backend rejects a settlement on a rejection.
      settlement:
        verb === 'aprobar'
          ? {
              replacementValue: this.replacementApplied(),
              settledAmount: this.amountToAuthorize() as number,
              adjustmentReason: this.settlementAdjusted() ? this.adjustmentReason().trim() : null,
            }
          : null,
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

  // ----- settlement amount -----
  // The backend computes it and explains it line by line; the analyst confirms or adjusts it with a
  // justification.

  protected readonly replacementInput = signal('');
  /** The value last sent to the backend; changing it refetches the proposal. */
  private readonly replacementApplied = signal<number | null>(null);

  /**
   * Always fetched, since resolved cases show it too. A 403 (insured) yields null and the card
   * hides it.
   */
  protected readonly settlement = toSignal(
    combineLatest([
      this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
      toObservable(this.reloadTrigger),
      toObservable(this.replacementApplied),
    ]).pipe(
      switchMap(([id, , replacementValue]) =>
        this.service
          .settlement(id as unknown as number, replacementValue)
          .pipe(catchError(() => of<Settlement | null>(null))),
      ),
    ),
    { initialValue: null as Settlement | null },
  );

  /** Signed by the analyst and awaiting the supervisor: the decision buttons give way to a notice. */
  protected readonly esperandoAutorizacion = computed(
    () => this.settlement()?.status === 'PENDING_AUTHORIZATION',
  );

  /** Returned by the supervisor, with a reason to fix. */
  protected readonly liquidacionDevuelta = computed(() => this.settlement()?.status === 'RETURNED');

  /** Computed on the proposal, so the analyst learns before confirming that it needs sign-off. */
  protected readonly requiereReferente = computed(() => {
    const limit = this.settlement()?.authorityLimit;
    const amount = this.amountToAuthorize();
    return limit != null && amount != null && amount > limit;
  });

  /**
   * REPAIR: it is the calculation base. TOTAL_LOSS: only matters when the coverage settles by the
   * lesser of sum insured and replacement value.
   */
  protected readonly pideMontoAcreditado = computed(() => {
    const s = this.settlement();
    return s?.formula === 'REPAIR' || s?.settlementBasis === 'LESSER_OF_SUM_AND_REPLACEMENT';
  });

  /** Offered only until the analyst enters a value; never applied automatically. */
  protected readonly sugerenciaDisponible = computed(() => {
    const s = this.settlement();
    return (
      this.pideMontoAcreditado() &&
      s?.suggestedFor === 'ACCREDITED_AMOUNT' &&
      s?.suggestedAmount != null &&
      this.replacementInput().trim() === '' &&
      this.replacementApplied() == null
    );
  });

  protected tomarSugerencia(): void {
    const amount = this.settlement()?.suggestedAmount;
    if (amount == null) {
      return;
    }
    this.replacementInput.set(String(amount));
    this.applyReplacementValue();
  }

  /**
   * The expert's payout for coverages that settle by sum insured (no accredited amount to fill in).
   * Hidden once the analyst types an amount.
   */
  protected readonly sugerenciaDeMontoDisponible = computed(() => {
    const s = this.settlement();
    return (
      s?.suggestedFor === 'SETTLED_AMOUNT' &&
      s?.suggestedAmount != null &&
      this.settledAmountInput().trim() === ''
    );
  });

  /** Taking it fills the analyst's amount, so it counts as an adjustment and needs a justification. */
  protected tomarSugerenciaDeMonto(): void {
    const amount = this.settlement()?.suggestedAmount;
    if (amount == null) {
      return;
    }
    this.settledAmountInput.set(String(amount));
  }

  protected applyReplacementValue(): void {
    const raw = this.replacementInput().trim();
    this.replacementApplied.set(raw === '' ? null : Number(raw));
  }

  /** Empty = pay the calculated amount. */
  protected readonly settledAmountInput = signal('');
  protected readonly adjustmentReason = signal('');

  /** Null while there is no proposal or the input isn't a number. */
  protected readonly amountToAuthorize = computed<number | null>(() => {
    const raw = this.settledAmountInput().trim();
    if (raw === '') {
      return this.settlement()?.calculatedAmount ?? null;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
  });

  /** An adjustment must be justified: that is what makes it auditable. */
  protected readonly settlementAdjusted = computed(() => {
    const proposed = this.settlement()?.calculatedAmount;
    const authorized = this.amountToAuthorize();
    return proposed != null && authorized != null && authorized !== proposed;
  });

  /** The sum insured is the indemnity ceiling (art. 3). */
  protected readonly settlementAboveSumInsured = computed(() => {
    const settlement = this.settlement();
    const authorized = this.amountToAuthorize();
    return settlement != null && authorized != null && authorized > settlement.sumInsured;
  });

  /** Null = can confirm. */
  protected readonly approvalBlockedReason = computed<string | null>(() => {
    if (!this.settlement()) {
      return 'No se pudo calcular el monto a pagar.';
    }
    if (this.amountToAuthorize() == null) {
      return 'El monto a pagar tiene que ser un número.';
    }
    if (this.settlementAboveSumInsured()) {
      return 'El monto no puede superar la suma asegurada.';
    }
    if (this.settlementAdjusted() && !this.adjustmentReason().trim()) {
      return 'Ajustaste el monto: hace falta justificar el ajuste.';
    }
    // The justification isn't listed: the field is already marked required and the button disabled.
    return null;
  });

  // ----- reopening a closed case -----
  // Shared with the supervisor, like assigning: reopening resolves nothing.
  protected readonly showReopen = signal(false);
  protected readonly reopenReason = signal('');
  protected readonly reopening = signal(false);
  protected readonly reopenError = signal<string | null>(null);

  protected readonly puedeReabrir = computed(
    () => this.canGestionar() && isEstadoFinal(this.data()?.status ?? ''),
  );

  askReopen(): void {
    this.reopenReason.set('');
    this.reopenError.set(null);
    this.showReopen.set(true);
  }

  cancelReopen(): void {
    this.showReopen.set(false);
  }

  confirmReopen(): void {
    const d = this.data();
    const reason = this.reopenReason().trim();
    if (!d || !reason) {
      return;
    }

    this.reopening.set(true);
    this.reopenError.set(null);
    this.service.reopen(d.id, reason).subscribe({
      next: () => {
        this.showReopen.set(false);
        this.reopening.set(false);
        this.reloadTrigger.update((v) => v + 1);
      },
      error: (err: HttpErrorResponse) => {
        this.reopening.set(false);
        this.reopenError.set(err.error?.detail || 'No se pudo reabrir el expediente');
      },
    });
  }

  // ----- referral to expert assessment -----
  // Not a verdict: it suspends the case to gather evidence, hence its own endpoint instead of
  // /decision.
  protected readonly derivado = computed(() => this.data()?.status === 'PENDING_EXPERT_REPORT');
  protected readonly enReparacion = computed(() => this.data()?.status === 'PENDING_REPAIR');

  /** Fetched with the case: needed to know whether to offer the button and to whom. */
  private readonly derivationOptions = this.optionsFor('ESTUDIO_LIQUIDADOR');
  private readonly repairOptions = this.optionsFor('SERVICIO_TECNICO');

  private optionsFor(providerType: ProviderType) {
    return toSignal(
      combineLatest([
        this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
        toObservable(this.reloadTrigger),
      ]).pipe(
        switchMap(([id]) =>
          this.service
            .derivationOptions(id as unknown as number, providerType)
            .pipe(catchError(() => of<OpcionesDerivacion | null>(null))),
        ),
      ),
      { initialValue: null as OpcionesDerivacion | null },
    );
  }

  protected readonly derivaciones = toSignal(
    combineLatest([
      this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
      toObservable(this.reloadTrigger),
    ]).pipe(
      switchMap(([id]) =>
        this.service
          .derivaciones(id as unknown as number)
          .pipe(catchError(() => of<Peritaje[]>([]))),
      ),
    ),
    { initialValue: [] as Peritaje[] },
  );

  protected readonly peritaje = computed(
    () => this.derivaciones().find((d) => d.providerType === 'ESTUDIO_LIQUIDADOR') ?? null,
  );
  private readonly reparacion = computed(
    () => this.derivaciones().find((d) => d.providerType === 'SERVICIO_TECNICO') ?? null,
  );

  protected readonly derivacionesTabLabel = computed(() => {
    if (this.peritaje() && this.reparacion()) {
      return 'Derivaciones';
    }
    return this.peritaje() ? 'Peritaje' : 'Servicio técnico';
  });

  /** Owner only, like deciding; the backend enforces it (409 unassigned, 403 someone else's). */
  protected readonly puedeDerivar = computed(() => this.enManosDelAnalista() && !this.peritaje());

  /**
   * Owner, pending, and not waiting on the referente: once the amount is sent for authorization
   * the analyst already decided, so deriving or reading "si aprobás" hints no longer applies.
   */
  private readonly enManosDelAnalista = computed(
    () => this.canDecide() && this.decisionState() === 'pending' && !this.esperandoAutorizacion(),
  );

  /**
   * Same owner rule as `puedeDerivar`: the backend refuses anyone but the assigned analyst. And
   * `eligible` is only true when the claim cause admits repair and there is a repair shop to send
   * it to; while loading or on error it is null, so the button stays hidden.
   */
  protected readonly puedeDerivarAReparacion = computed(
    () =>
      this.enManosDelAnalista() && !this.reparacion() && this.repairOptions()?.eligible === true,
  );

  /** Enabled by the insurer's rule AND with experts to send it to. */
  protected readonly derivacionHabilitada = computed(
    () => this.derivationOptions()?.eligible === true,
  );

  /**
   * Suggested for high or critical risk bands (deterministic, not a sixth classification); it only
   * highlights the button.
   */
  protected readonly derivacionSugerida = computed(() => {
    const band = this.data()?.riskBand;
    return (band === 'HIGH' || band === 'CRITICAL') && this.derivacionHabilitada();
  });

  protected readonly sugerenciaDerivacion = computed<string | null>(() => {
    if (!this.derivacionSugerida()) {
      return null;
    }
    const band = this.data()?.riskBand as RiskBand;
    return `Riesgo ${riskBandLabel(band).toLowerCase()}: se sugiere derivar a peritaje antes de decidir.`;
  });

  protected readonly opcionesDerivacion = computed<{ tipo: ProviderType; label: string }[]>(() => [
    ...(this.puedeDerivar() && this.derivacionHabilitada()
      ? [{ tipo: 'ESTUDIO_LIQUIDADOR' as ProviderType, label: 'Derivar a peritaje' }]
      : []),
    ...(this.puedeDerivarAReparacion()
      ? [{ tipo: 'SERVICIO_TECNICO' as ProviderType, label: 'Derivar a servicio técnico' }]
      : []),
  ]);

  /** The calculated amount exceeds the branch limit: approving needs the supervisor's sign-off. */
  protected readonly superaAtribucion = computed(() => {
    const s = this.settlement();
    return (
      this.enManosDelAnalista() &&
      s?.authorityLimit != null &&
      s.calculatedAmount > s.authorityLimit
    );
  });

  protected readonly hayAvisos = computed(
    () =>
      this.showCauseConsistency() ||
      (this.canAct() && this.liquidacionDevuelta() && this.settlement() != null) ||
      this.superaAtribucion() ||
      (this.puedeDerivar() && !!this.sugerenciaDerivacion()),
  );

  // ----- "Antes de decidir" -----
  // Brings existing evidence next to the buttons; adds no criteria and suggests nothing.

  protected readonly plazoTexto = computed(() => {
    const d = this.data();
    if (!d?.responseDeadline) {
      return null;
    }
    const fecha = formatDate(d.responseDeadline);
    return isDeadlinePrioritized(d.deadlinePriority)
      ? `${deadlinePriorityLabel(d.deadlinePriority, d.responseDeadline)} · ${fecha}`
      : `Vence el ${fecha}`;
  });

  protected readonly plazoTone = computed<StatusTone>(() => {
    const d = this.data();
    return d && isDeadlinePrioritized(d.deadlinePriority)
      ? deadlinePriorityTone(d.deadlinePriority)
      : 'neutral';
  });

  /** Referrals that already came back. */
  protected readonly derivacionesRespondidas = computed(() =>
    this.derivaciones().filter((p) => p.verdict || p.repairOutcome),
  );

  protected resultadoDerivacion(p: Peritaje): string {
    const esReparacion = p.providerType === 'SERVICIO_TECNICO';
    const resultado = p.verdict
      ? veredictoLabel(p.verdict)
      : repairOutcomeLabel(p.repairOutcome ?? '');
    const monto = esReparacion ? p.repairCost : p.indemnifiableAmount;
    const quien = esReparacion ? 'Servicio técnico' : 'Perito';
    return monto != null
      ? `${quien}: ${resultado} · ${this.formatMonto(monto)}`
      : `${quien}: ${resultado}`;
  }

  protected resultadoDerivacionTone(p: Peritaje): StatusTone {
    return p.verdict ? veredictoTone(p.verdict) : 'neutral';
  }

  /** Signals that pull toward a closer look, each one pointing at the tab with its evidence. */
  protected readonly alertasDecision = computed<BriefAlert[]>(() => {
    const alertas: BriefAlert[] = [];
    const fallidas = this.reglasFallidas();
    if (fallidas > 0) {
      alertas.push({
        label: fallidas === 1 ? '1 regla no cumplida' : `${fallidas} reglas no cumplidas`,
        tab: 'reglas',
      });
    }
    if (this.hayCoincidenciasImagen()) {
      alertas.push({ label: 'Imágenes con coincidencias', tab: 'imagenes' });
    }
    const previos = this.antecedentesPrevios().length;
    if (previos > 0) {
      alertas.push({
        label: previos === 1 ? '1 antecedente de fraude' : `${previos} antecedentes de fraude`,
        tab: 'asegurado',
      });
    }
    if (this.unreadMessages() > 0) {
      alertas.push({ label: 'Mensajes sin leer del asegurado', tab: 'conversacion' });
    }
    return alertas;
  });

  /** Tells the analyst whether it is company policy or missing expert firms. */
  protected readonly motivoNoDerivable = computed<string | null>(() => {
    const options = this.derivationOptions();
    if (!options || options.eligible) {
      return null;
    }
    if (options.minClaimedAmount == null) {
      return 'Esta aseguradora no deriva a peritaje los siniestros de este ramo.';
    }
    if (options.firms.length === 0) {
      return 'No hay peritos cargados para este ramo.';
    }
    // Names the expert assessment explicitly so it isn't read as blocking the repair shop too.
    return (
      `El monto reclamado no alcanza el mínimo para derivar a peritaje ` +
      `(${this.formatMonto(options.minClaimedAmount)}).`
    );
  });

  protected readonly tipoDerivacion = signal<ProviderType>('ESTUDIO_LIQUIDADOR');
  protected readonly esReparacion = computed(() => this.tipoDerivacion() === 'SERVICIO_TECNICO');

  protected readonly peritoOptions = computed<SelectOption[]>(() =>
    ((this.esReparacion() ? this.repairOptions() : this.derivationOptions())?.firms ?? []).map(
      (firm) => ({
        value: String(firm.id),
        // Branch tells specialist from generalist; area matters because the device must be inspected in
        // person.
        label: [firm.name, firm.branchName ?? 'todos los ramos', firm.zone]
          .filter(Boolean)
          .join(' · '),
      }),
    ),
  );

  protected readonly showDerivar = signal(false);
  protected readonly peritoElegido = signal('');
  protected readonly motivoDerivacion = signal('');
  protected readonly derivarSaving = signal(false);
  protected readonly derivarError = signal<string | null>(null);

  askDerivar(tipo: ProviderType = 'ESTUDIO_LIQUIDADOR'): void {
    this.tipoDerivacion.set(tipo);
    this.peritoElegido.set('');
    this.motivoDerivacion.set('');
    this.derivarError.set(null);
    this.showDerivar.set(true);
  }

  cancelDerivar(): void {
    this.showDerivar.set(false);
  }

  confirmDerivar(): void {
    const d = this.data();
    const perito = this.peritoElegido();
    const motivo = this.motivoDerivacion().trim();
    if (!d || !perito || !motivo) {
      return;
    }
    this.derivarSaving.set(true);
    this.derivarError.set(null);
    this.service.derivarAPeritaje(d.id, Number(perito), motivo, this.tipoDerivacion()).subscribe({
      next: (peritaje) => {
        this.derivarSaving.set(false);
        this.showDerivar.set(false);
        this.derivacionHechaCaseId.set(d.id);
        this.derivacionHecha.set(peritaje);
        this.reloadTrigger.update((v) => v + 1);
      },
      error: (err: HttpErrorResponse) => {
        this.derivarSaving.set(false);
        this.derivarError.set(err.error?.detail || 'No se pudo derivar el expediente');
      },
    });
  }

  /** Kept for the confirmation modal: the only signal that the email went out. */
  protected readonly derivacionHecha = signal<Peritaje | null>(null);
  // Kept apart from data(), which is empty while the case reloads behind the modal.
  protected readonly derivacionHechaCaseId = signal<number | null>(null);

  cerrarDerivacionHecha(): void {
    this.derivacionHecha.set(null);
  }

  // ----- expert report / repair shop response -----
  protected readonly showInforme = signal(false);
  protected readonly informeTipo = signal<ProviderType>('ESTUDIO_LIQUIDADOR');
  protected readonly informeEsReparacion = computed(
    () => this.informeTipo() === 'SERVICIO_TECNICO',
  );
  protected readonly repairOutcomeOptions: SelectOption[] = REPAIR_OUTCOME_OPTIONS;
  protected readonly veredicto = signal('');
  protected readonly notaVeredicto = signal('');
  protected readonly informeFile = signal<File | null>(null);
  protected readonly informeSaving = signal(false);
  protected readonly informeError = signal<string | null>(null);

  /**
   * Saving "fraude confirmado" also records a fraud mark on the person, which can't be removed from
   * the app.
   */
  protected readonly veredictoConfirmaFraude = computed(
    () => !this.informeEsReparacion() && this.veredicto() === 'FRAUD_CONFIRMED',
  );

  protected readonly veredictoOptions: SelectOption[] = [
    { value: 'FRAUD_CONFIRMED', label: 'Fraude confirmado' },
    { value: 'FRAUD_DISCARDED', label: 'Fraude descartado' },
    { value: 'INCONCLUSIVE', label: 'No concluyente' },
  ];

  askInforme(tipo: ProviderType = 'ESTUDIO_LIQUIDADOR'): void {
    this.informeTipo.set(tipo);
    this.veredicto.set('');
    this.notaVeredicto.set('');
    this.montoInforme.set('');
    this.informeFile.set(null);
    this.informeError.set(null);
    this.showInforme.set(true);
  }

  cancelInforme(): void {
    this.showInforme.set(false);
  }

  /**
   * Expert: the determined claim value; repair shop: the repair cost. Label, requiredness and target
   * column differ accordingly.
   */
  protected readonly montoInforme = signal('');

  /** Not asked for IRREPARABLE (the backend rejects it); always offered to the expert. */
  protected readonly pideMontoDelInforme = computed(
    () =>
      !this.informeEsReparacion() ||
      this.veredicto() === 'QUOTE_SENT' ||
      this.veredicto() === 'REPAIRED',
  );

  /** Already repaired and billed: the amount is an invoice, not a quote. */
  protected readonly informeEsFactura = computed(
    () => this.informeEsReparacion() && this.veredicto() === 'REPAIRED',
  );

  /** A quote needs an amount; the invoice may come later and the expert's amount is optional. */
  protected readonly montoDelInformeObligatorio = computed(
    () => this.informeEsReparacion() && this.veredicto() === 'QUOTE_SENT',
  );

  protected readonly montoInformeFaltante = computed(
    () => this.montoDelInformeObligatorio() && this.montoInformeNumero() == null,
  );

  private montoInformeNumero(): number | null {
    if (!this.pideMontoDelInforme()) {
      return null;
    }
    const raw = this.montoInforme().trim();
    if (raw === '') {
      return null;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
  }

  onInformeFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.informeFile.set(input.files?.[0] ?? null);
  }

  confirmInforme(): void {
    const d = this.data();
    const file = this.informeFile();
    const result = this.veredicto();
    if (!d || !file || !result || this.montoInformeFaltante()) {
      return;
    }
    this.informeSaving.set(true);
    this.informeError.set(null);
    const note = this.notaVeredicto().trim();
    const monto = this.montoInformeNumero();
    const request = this.informeEsReparacion()
      ? this.service.cargarRespuestaServicioTecnico(
          d.id,
          result as RepairOutcome,
          note,
          monto,
          file,
        )
      : this.service.cargarInformePericial(d.id, result as ExpertVerdict, note, monto, file);
    request.subscribe({
      next: () => {
        this.informeSaving.set(false);
        this.showInforme.set(false);
        this.reloadTrigger.update((v) => v + 1);
      },
      error: (err: HttpErrorResponse) => {
        this.informeSaving.set(false);
        this.informeError.set(err.error?.detail || 'No se pudo cargar el informe');
      },
    });
  }

  // ----- insured's fraud record -----
  // Separate from the expert report: carrying a finding over to the person's future claims is the
  // analyst's act, recorded with their name and reason (Ley 25.326).
  private readonly antecedentes = toSignal(
    combineLatest([
      this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
      toObservable(this.reloadTrigger),
    ]).pipe(
      switchMap(([id]) =>
        this.service
          .antecedentesFraude(id as unknown as number)
          .pipe(catchError(() => of<AntecedenteFraude[]>([]))),
      ),
    ),
    { initialValue: [] as AntecedenteFraude[] },
  );

  /** From OTHER cases. */
  protected readonly antecedentesPrevios = computed(() =>
    this.antecedentes().filter((a) => a.caseId !== this.data()?.id),
  );

  /** A case yields at most one record. */
  protected readonly antecedentePropio = computed(
    () => this.antecedentes().find((a) => a.caseId === this.data()?.id) ?? null,
  );

  /** Analyst only, once, after classification; never on APPROVED. The backend enforces it too. */
  protected readonly puedeRegistrarAntecedente = computed(() => {
    const status = this.data()?.status;
    return (
      this.canAct() &&
      !this.antecedentePropio() &&
      (status === 'PENDING_ANALYST_REVIEW' || status === 'REJECTED')
    );
  });

  protected readonly mostrarAntecedente = computed(
    () =>
      this.antecedentesPrevios().length > 0 ||
      this.antecedentePropio() != null ||
      this.puedeRegistrarAntecedente(),
  );

  /** Defaults to expert-backed when fraud was confirmed. */
  private readonly peritajeConfirmaFraude = computed(
    () => this.peritaje()?.verdict === 'FRAUD_CONFIRMED',
  );

  /** Not offered without a confirmed expert verdict: the backend validates against the saved one. */
  protected readonly origenOptions = computed<SelectOption[]>(() => [
    ...(this.peritajeConfirmaFraude()
      ? [{ value: 'EXPERT_BACKED', label: origenAntecedenteLabel('EXPERT_BACKED') }]
      : []),
    { value: 'ANALYST_DECLARED', label: origenAntecedenteLabel('ANALYST_DECLARED') },
  ]);

  protected readonly showAntecedente = signal(false);
  protected readonly origenElegido = signal('');
  protected readonly motivoAntecedente = signal('');
  protected readonly antecedenteSaving = signal(false);
  protected readonly antecedenteError = signal<string | null>(null);

  protected readonly motivoAntecedenteMin = MOTIVO_ANTECEDENTE_MIN;

  /** Warned while typing, not on the 400. */
  protected readonly motivoAntecedenteCorto = computed(
    () => this.motivoAntecedente().trim().length < MOTIVO_ANTECEDENTE_MIN,
  );

  protected readonly efectoOrigenElegido = computed(() =>
    this.origenElegido() === 'EXPERT_BACKED'
      ? 'Va a sumar al nivel de riesgo de sus próximas denuncias y, si la aseguradora lo configuró así, a impedirles la vía rápida.'
      : 'Va a aparecer como alerta en sus próximas denuncias, pero no suma al nivel de riesgo: sin peritaje detrás, una sospecha que mueve el score termina alimentándose sola.',
  );

  askAntecedente(): void {
    this.origenElegido.set(this.peritajeConfirmaFraude() ? 'EXPERT_BACKED' : 'ANALYST_DECLARED');
    this.motivoAntecedente.set('');
    this.antecedenteError.set(null);
    this.showAntecedente.set(true);
  }

  cancelAntecedente(): void {
    this.showAntecedente.set(false);
  }

  confirmAntecedente(): void {
    const d = this.data();
    const source = this.origenElegido() as OrigenAntecedente;
    const reason = this.motivoAntecedente().trim();
    if (!d || !source || this.motivoAntecedenteCorto()) {
      return;
    }
    this.antecedenteSaving.set(true);
    this.antecedenteError.set(null);
    this.service.registrarAntecedente(d.id, { source, reason }).subscribe({
      next: () => {
        this.antecedenteSaving.set(false);
        this.showAntecedente.set(false);
        this.reloadTrigger.update((v) => v + 1);
      },
      error: (err: HttpErrorResponse) => {
        this.antecedenteSaving.set(false);
        this.antecedenteError.set(err.error?.detail || 'No se pudo registrar el antecedente');
      },
    });
  }

  estadoAntecedenteLabel = estadoAntecedenteLabel;
  estadoAntecedenteTone = estadoAntecedenteTone;
  efectoAntecedente = efectoAntecedente;
  origenAntecedenteLabel = origenAntecedenteLabel;

  veredictoLabel = veredictoLabel;
  veredictoTone = veredictoTone;
  repairOutcomeLabel = repairOutcomeLabel;
  formatDateTime = formatDateTime;

  ruleTypeLabel = ruleTypeLabel;
  ruleResultLabel = ruleResultLabel;
  ruleResultTone = ruleResultTone;
  advisoryResultLabel = advisoryResultLabel;
  advisoryResultTone = advisoryResultTone;
  ruleEvaluationText = ruleEvaluationText;

  private formatMonto(amount: number): string {
    return new Intl.NumberFormat('es-AR', {
      style: 'currency',
      currency: 'ARS',
      maximumFractionDigits: 0,
    }).format(amount);
  }

  /** Like {@link formatMonto} but with cents: rounding would show a figure other than the one saved. */
  protected montoExacto(amount: number | null): string {
    if (amount == null) {
      return '—';
    }
    return new Intl.NumberFormat('es-AR', {
      style: 'currency',
      currency: 'ARS',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(amount);
  }

  // ----- manual classification retry (CLASSIFICATION_FAILED) -----
  // The scheduler only sweeps PENDING_CLASSIFICATION, so exhausted cases must be requeued by hand.
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

  // ----- assignment -----
  // Both operational roles can assign; only the analyst can take a case for themselves.
  protected readonly assignSaving = signal(false);
  protected readonly assignError = signal<string | null>(null);

  // Prevents opening the menu while the analyst list is loading and showing it empty.
  protected readonly analystsLoading = signal(true);

  private readonly analysts = toSignal(
    this.users.listAnalysts().pipe(
      catchError(() => of([])),
      finalize(() => this.analystsLoading.set(false)),
    ),
    { initialValue: [] },
  );

  protected readonly analystMenuItems = computed<MenuItem[]>(() => {
    // The current assignee is left out.
    const assignedId = this.data()?.assignedAnalystId;
    return this.analysts()
      .filter((a) => a.id !== assignedId)
      .map((a) => ({ value: String(a.id), label: `${a.nombre} ${a.apellido}` }));
  });

  /**
   * Per-tenant analyst id, found by email in the tenant-scoped analyst list (the session only has
   * the user id). Null for the supervisor.
   */
  private readonly myAnalystId = computed<number | null>(() => {
    const email = this.session.session()?.email;
    return this.analysts().find((a) => a.email === email)?.id ?? null;
  });

  protected readonly canTake = computed(
    () => this.session.session()?.rol === 'ANALISTA_SINIESTROS' && this.myAnalystId() != null,
  );

  /** The supervisor doesn't decide; the backend rejects it with 403. */
  protected readonly canAct = computed(() => this.session.session()?.rol === 'ANALISTA_SINIESTROS');

  /**
   * Moving the case without resolving it; shared with the supervisor since none of these is a
   * decision.
   */
  protected readonly canGestionar = computed(() => {
    const rol = this.session.session()?.rol;
    return rol === 'ANALISTA_SINIESTROS' || rol === 'REFERENTE_ASEGURADORA';
  });

  protected readonly assignedName = computed(() => this.data()?.assignedAnalystName ?? null);
  protected readonly isAssigned = computed(() => this.data()?.assignedAnalystId != null);

  protected readonly esperaDecisionTexto = computed(() => {
    const analista = this.assignedName();
    return analista
      ? `Espera la decisión de ${analista}. Podés reasignarlo si hace falta.`
      : 'Espera la decisión de un analista. Asignalo para que alguien lo tome.';
  });

  protected readonly isMine = computed(() => {
    const id = this.data()?.assignedAnalystId;
    return id != null && id === this.myAnalystId();
  });

  /**
   * Only the assignee decides (backend: 403 someone else's, 409 unassigned); hiding it here points
   * to "Asignarme" instead of a generic error.
   */
  protected readonly canDecide = computed(() => this.canAct() && this.isMine());

  /** Why an analyst who isn't the assignee can't act (decide or refer). */
  protected readonly decisionBlockedReason = computed(() => {
    if (!this.isAssigned()) {
      return 'Asignate el expediente para decidir o derivarlo.';
    }
    const analista = this.assignedName();
    return analista ? `Asignado a ${analista}` : 'Asignado a otro analista';
  });

  protected readonly analystInitials = computed(() =>
    (this.assignedName() ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]!.toUpperCase())
      .join(''),
  );

  /**
   * Assignment date from the history: an assignment leaves an entry with fromStatus == toStatus and
   * actor ANALYST or REFERENT; the last one is the current assignment.
   */
  private readonly assignedSince = computed<string | null>(() => {
    const milestones = (this.data()?.statusHistory ?? []).filter(
      (t) => t.fromStatus === t.toStatus && (t.actor === 'ANALYST' || t.actor === 'REFERENT'),
    );
    const last = milestones.at(-1);
    return last
      ? new Date(last.changedAt).toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit' })
      : null;
  });

  /** e.g. "Vos · desde el 04/06". */
  protected readonly assignedContext = computed(() => {
    const parts: string[] = [];
    if (this.isMine()) parts.push('Vos');
    const since = this.assignedSince();
    if (since) parts.push(`desde el ${since}`);
    return parts.join(' · ');
  });

  protected readonly reasignarMenuItems = computed<MenuItem[]>(() => [
    ...this.analystMenuItems(),
    { value: 'release', label: 'Liberar', danger: true },
  ]);

  protected onReasignarMenu(value: string): void {
    if (value === 'release') {
      this.release();
    } else {
      this.assignTo(value);
    }
  }

  protected readonly derivarMenuItems = computed<MenuItem[]>(() =>
    this.opcionesDerivacion().map((o) => ({ value: o.tipo, label: o.label })),
  );

  protected onDerivarMenu(value: string): void {
    this.askDerivar(value as ProviderType);
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
        // Refetch: assignment also adds a history entry.
        this.reloadTrigger.update((v) => v + 1);
      },
      error: (err: HttpErrorResponse) => {
        this.assignSaving.set(false);
        this.assignError.set(err.error?.detail || 'No se pudo actualizar la asignación');
      },
    });
  }

  // ----- missing documentation (read-only for the analyst) -----
  protected readonly needsDocs = computed(
    () => this.data()?.analysisClassification === 'FALTA_DOCUMENTACION',
  );

  /** "Resolución" only applies when there is or was something to decide. */
  protected readonly decisionCardHeading = computed(() =>
    this.decisionState() === 'not-ready' && this.needsDocs() && !this.derivado() && !this.isFailed()
      ? 'Estado del expediente'
      : 'Resolución',
  );

  /** Refreshed with the same trigger as the case. */
  protected readonly docsReloadToken = computed(() => this.reloadTrigger());

  // Explicit loading state: while this separate request is in flight, an empty documents() would
  // list every required type as missing.
  private readonly documentsState = toSignal(
    combineLatest([
      this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
      toObservable(this.reloadTrigger),
    ]).pipe(
      switchMap(([id]) =>
        id
          ? this.service.listDocuments(Number(id)).pipe(
              map((list): DocsState => ({ status: 'ok', list })),
              startWith<DocsState>({ status: 'loading' }),
              catchError(() => of<DocsState>({ status: 'ok', list: [] })),
            )
          : of<DocsState>({ status: 'ok', list: [] }),
      ),
    ),
    { initialValue: { status: 'loading' } as DocsState },
  );

  protected readonly documentsLoading = computed(() => this.documentsState().status === 'loading');

  private readonly documents = computed<CaseDocument[]>(() => {
    const s = this.documentsState();
    return s.status === 'ok' ? s.list : [];
  });

  private readonly agenda = inject(DocumentAgendaService);

  private readonly requiredDocTypes = toSignal(
    toObservable(
      computed(() => ({
        branch: this.data()?.branch ?? null,
        claimCause: this.data()?.claimCause ?? null,
      })),
    ).pipe(
      switchMap(({ branch, claimCause }) =>
        branch && claimCause
          ? this.agenda.slotsForBranch(branch, claimCause)
          : of(CASE_DOCUMENT_TYPES),
      ),
    ),
    { initialValue: CASE_DOCUMENT_TYPES as readonly CaseDocumentType[] },
  );

  protected readonly missingDocLabels = computed(() => {
    const present = new Set(this.documents().map((d) => d.type));
    return this.requiredDocTypes()
      .filter((t) => !present.has(t.type))
      .map((t) => t.label);
  });
}
