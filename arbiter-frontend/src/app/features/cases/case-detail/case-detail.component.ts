import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  linkedSignal,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import {
  catchError,
  combineLatest,
  debounceTime,
  finalize,
  map,
  Observable,
  of,
  startWith,
  switchMap,
} from 'rxjs';

import { CaseService, AnalystDecisionRequest, ClaimCauseOption, Settlement } from '../case.service';
import { DocumentAgendaService } from '../document-agenda.service';
import { CaseNavigationService } from '../case-navigation.service';
import { CaseMessagesService } from '../case-messages.service';
import { CaseMessage } from '../../../core/models/case-message';
import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { UserAdminService } from '../../../core/auth/user-admin.service';
import { SettlementAuthoritiesService } from '../../admin/settlement-authorities.service';
import {
  DocumentAnalysis,
  CaseResponse,
  RiskBreakdownItem,
  StatusTransition,
} from '../../../core/models/case';
import { withDocumentLabels, riskFactorLabel } from '../../../core/models/business-rules';
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
} from '../../../core/models/traceability';
import {
  CASE_DOCUMENT_TYPES,
  CaseDocument,
  CaseDocumentType,
  documentTypeLabel,
} from '../../../core/models/case-document';
import { classificationLabel, classificationTone } from '../../../core/models/classification';
import { forensicAlertLevel } from '../../../core/models/forensic';
import {
  causeConsistencyLabel,
  causeConsistencyTone,
  shouldSurfaceCauseConsistency,
} from '../../../core/models/cause-consistency';
import {
  Derivacion,
  ExpertVerdict,
  DerivationOptions,
  ProviderType,
  ramosLabel,
  REPAIR_OUTCOME_OPTIONS,
  RepairOutcome,
  repairOutcomeLabel,
  verdictLabel,
  verdictTone,
} from '../../../core/models/derivacion';
import {
  FraudRecord,
  FRAUD_RECORD_REASON_MIN,
  FraudRecordOrigin,
  fraudRecordEffect,
  fraudRecordStatusLabel,
  fraudRecordStatusTone,
  fraudRecordOriginLabel,
} from '../../../core/models/fraud-record';
import {
  caseStatusLabel,
  simplifiedStatusLabel,
  caseStatusTone,
  isFinalStatus,
  riskBandEmptyLabel,
} from '../../../core/models/case-status';
import {
  deadlinePriorityLabel,
  deadlinePriorityTone,
  isDeadlinePrioritized,
} from '../../../core/models/deadline-priority';
import { RiskBand, riskBandLabel } from '../../../core/models/risk-band';
import { StatusTone } from '../../../core/models/status-tone';
import { chatListStamp, formatDate, formatDateTime } from '../../../core/util/datetime';
import { amountInputDisplay, amountInputLabel, amountInputValue } from '../../../core/util/money';
import { FraudGaugeComponent } from '../../../shared/ui/fraud-gauge/fraud-gauge.component';
import { InfoTipComponent } from '../../../shared/ui/info-tip/info-tip.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { StatusTimelineComponent } from '../../../shared/ui/status-timeline/status-timeline.component';
import { ForensicAnalysisComponent } from './forensic-analysis/forensic-analysis.component';
import { CaseDocumentsComponent } from '../case-documents/case-documents.component';
import { CaseChatPopupComponent } from '../case-chat-popup/case-chat-popup.component';
import { analystQuickReplies } from '../case-chat/quick-replies';
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
  | { status: 'ok'; data: CaseResponse }
  | { status: 'error'; httpStatus: number };

type DocsState = { status: 'loading' } | { status: 'ok'; list: CaseDocument[] };

type TabId =
  'summary' | 'analysis' | 'rules' | 'documents' | 'images' | 'insured' | 'assessment' | 'history';
type Verb = 'approve' | 'reject';

/** null renders as "Sin datos": the backend doesn't provide it. */
interface FieldItem {
  label: string;
  value: string | null;
  mono?: boolean;
  full?: boolean;
  sub?: string;
}

/** Hours for the first couple of days, days afterwards. */
function reportDelay(eventDate: string, createdAt: string): string | undefined {
  const hours = Math.round((Date.parse(createdAt) - Date.parse(eventDate)) / 3_600_000);
  if (Number.isNaN(hours) || hours < 0) {
    return undefined;
  }
  if (hours < 48) {
    return hours === 1 ? '1 h después del hecho' : `${hours} h después del hecho`;
  }
  return `${Math.round(hours / 24)} días después del hecho`;
}

/** A signal worth reading before deciding, and the tab where its evidence lives. */
function initialsOf(name: string | null | undefined): string {
  return (name ?? '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]!.toUpperCase())
    .join('');
}

interface BriefAlert {
  label: string;
  /** 'chat' opens the chat popup instead of a tab. */
  tab: TabId | 'chat';
}

@Component({
  selector: 'app-case-detail',
  imports: [
    RouterLink,
    FraudGaugeComponent,
    InfoTipComponent,
    EmptyStateComponent,
    StatusTimelineComponent,
    ForensicAnalysisComponent,
    CaseDocumentsComponent,
    CaseChatPopupComponent,
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
  templateUrl: './case-detail.component.html',
  // Order matters: both files are concatenated in this order and the cascade depends on it.
  styleUrls: [
    './case-detail.component.scss',
    './case-detail-panels.scss',
    './case-detail-analysis.scss',
  ],
})
export class CaseDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly service = inject(CaseService);
  private readonly caseNav = inject(CaseNavigationService);
  private readonly session = inject(AuthSessionService);
  private readonly users = inject(UserAdminService);
  private readonly settlementAuthorities = inject(SettlementAuthoritiesService);
  private readonly messages = inject(CaseMessagesService);

  constructor() {
    // The dot has to be there before the analyst opens the tab, so the count is fetched with the
    // case and not when the thread mounts. Keyed on the id and not on data(), which comes back as a
    // new object on every refetch and asked for the thread twice per open.
    effect(() => {
      const id = this.loadedCaseId();
      untracked(() => {
        this.unreadMessages.set(0);
        this.lastMessage.set(null);
        if (id) {
          this.messages.thread(id).subscribe({
            next: (thread) => {
              this.unreadMessages.set(thread.unread);
              this.lastMessage.set(thread.messages.at(-1) ?? null);
            },
            error: () => undefined,
          });
        }
      });
    });
  }

  protected readonly lastMessage = signal<CaseMessage | null>(null);

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

  protected readonly data = computed<CaseResponse | null>(() => {
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
    return d ? caseStatusLabel(d.status) : '';
  });

  protected readonly statusTone = computed<StatusTone>(() => {
    const d = this.data();
    return d ? caseStatusTone(d.status) : 'neutral';
  });

  protected readonly simplifiedStatusLabel = computed(() => {
    const d = this.data();
    return d ? simplifiedStatusLabel(d.status) : '';
  });

  private static readonly RISK_BAND_GAUGE: Record<string, 1 | 2 | 3 | 4> = {
    LOW: 1,
    MEDIUM: 2,
    HIGH: 3,
    CRITICAL: 4,
  };

  protected readonly riskGaugeBand = computed<1 | 2 | 3 | 4 | null>(() => {
    const band = this.data()?.riskBand;
    return band ? CaseDetailComponent.RISK_BAND_GAUGE[band] : null;
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
  protected scoreContribution(item: RiskBreakdownItem): number {
    const total = this.weightedSum();
    const score = this.data()?.riskScore ?? 0;
    return total === 0 ? 0 : Math.round((item.weightedContribution / total) * score * 100);
  }

  protected readonly classificationLabel = computed(() => {
    const d = this.data();
    return d ? classificationLabel(d.analysisClassification) : '';
  });

  protected readonly classificationTone = computed<StatusTone>(() => {
    const d = this.data();
    return d ? classificationTone(d.analysisClassification) : 'neutral';
  });

  /** Only with something to look at: MATCHES adds nothing and null means it didn't run. */
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

  /** Empty for Fast Track, missing documentation or before classification; the tab is hidden then. */
  protected readonly analysisReasons = computed<string[]>(() =>
    (this.data()?.analysisReasons ?? []).map(withDocumentLabels),
  );

  /** Empty until classified, or when no document was read. */
  protected readonly documentAnalyses = computed<DocumentAnalysis[]>(
    () => this.data()?.documentAnalyses ?? [],
  );

  protected documentLabel(type: string): string {
    return documentTypeLabel(type);
  }

  /**
   * Settled by the rules engine without the LLM, so no model confidence is shown: the backend's 100%
   * is a fixed value, not a measurement.
   */
  protected readonly isDeterministicOutcome = computed(() => this.data()?.resolvedByRules === true);

  protected readonly summaryGroups = computed<{ heading: string; fields: FieldItem[] }[]>(() => {
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
            value: d?.claimedAmount ? this.formatAmount(d.claimedAmount) : null,
            sub:
              d?.claimedAmount && sumInsured
                ? `${Math.round((d.claimedAmount / sumInsured) * 100)}% de la suma asegurada`
                : undefined,
          },
          {
            label: 'Fecha de denuncia',
            value: d?.createdAt ? formatDateTime(d.createdAt) : null,
            sub: d?.createdAt && d.eventDate ? reportDelay(d.eventDate, d.createdAt) : undefined,
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

  private readonly ruleResults = computed<RuleResult[]>(() => this.data()?.ruleResults ?? []);

  /** Kept apart from the Fast Track criteria: failing one means something different in each table. */
  protected readonly hardRuleResults = computed<RuleResult[]>(() =>
    this.ruleResults().filter(
      (r) => !isFastTrackCriterion(r.ruleType) && !isAdvisoryCheck(r.ruleType),
    ),
  );

  /** Kept above the rules: a failed advisory among them would read as an exclusion. */
  protected readonly advisoryChecks = computed<RuleResult[]>(() =>
    this.ruleResults().filter((r) => isAdvisoryCheck(r.ruleType)),
  );

  protected readonly hasAdvisoryWarning = computed(() =>
    this.advisoryChecks().some((r) => r.result === 'FAIL'),
  );

  /** Which advisories warn, so the card's intro only mentions those. */
  protected readonly advisoryWarningTypes = computed(
    () =>
      new Set(
        this.advisoryChecks()
          .filter((r) => r.result === 'FAIL')
          .map((r) => r.ruleType),
      ),
  );

  /** Present both when the case took Fast Track (why) and when it didn't (which criterion failed). */
  protected readonly fastTrackCriteria = computed<RuleResult[]>(() =>
    this.ruleResults().filter((r) => isFastTrackCriterion(r.ruleType)),
  );

  protected readonly isFastTrack = computed(
    () => this.data()?.analysisClassification === 'FAST_TRACK',
  );

  /** The load failed, as opposed to "no rules ran", which is a fact about the case. */
  protected readonly ruleResultsUnavailable = computed(() => this.data()?.ruleResults === null);

  /**
   * Never Fast Track: the gate runs after the hard rules. Empty when the insurer has no active rules
   * or the claim stopped at the mandatory-documents check, which returns before the other evaluators.
   */
  protected readonly noRulesReason = computed(() => {
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
  protected readonly hasAnalysis = computed(
    () => this.data()?.riskScore != null || this.analysisReasons().length > 0,
  );

  /** Hidden before classification: "no active rules" would be false, they just haven't run yet. */
  protected readonly hasRules = computed(
    () =>
      this.ruleResults().length > 0 ||
      this.ruleResultsUnavailable() ||
      this.policySnapshot() != null ||
      !!this.data()?.analysisClassification,
  );

  protected readonly failedRules = computed(
    () => this.hardRuleResults().filter((r) => r.result === 'FAIL').length,
  );

  protected readonly passedRules = computed(
    () => this.hardRuleResults().filter((r) => r.result === 'PASS').length,
  );

  protected readonly scoreSegments = computed(() =>
    this.riskBreakdown()
      .map((item) => ({ factorId: item.factorId, aporte: this.scoreContribution(item) }))
      .filter((s) => s.aporte > 0),
  );

  /** The summary card only answers "why this band": zero-contribution factors explain nothing. */
  protected readonly topRiskFactors = computed(() => this.scoreSegments().slice(0, 2));

  protected readonly scoreTone = computed<StatusTone>(() => {
    const tones: Record<number, StatusTone> = { 1: 'ok', 2: 'warning', 3: 'risk', 4: 'danger' };
    const band = this.riskGaugeBand();
    return band ? tones[band] : 'neutral';
  });

  protected readonly policySnapshot = computed<PolicySnapshot | null>(
    () => this.data()?.policySnapshot ?? null,
  );

  // Lazy-loaded from their own endpoint: an insurer-DB query most case views never need.
  private readonly policyItems = toSignal(
    combineLatest([
      this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
      toObservable(this.reloadTrigger),
    ]).pipe(
      switchMap(([id]) =>
        this.service
          .insuredPolicies(id as unknown as number)
          .pipe(catchError(() => of<Policy[]>([]))),
      ),
    ),
    { initialValue: [] as Policy[] },
  );

  /** All of them, the claim's own policy included. */
  protected readonly insuredPolicies = computed<Policy[]>(() => this.policyItems());

  /**
   * Only current policies are listed, so the claim's policy is missing if it expired after the
   * claim; the title is omitted then.
   */
  protected readonly policyGroups = computed<{ heading: string; policies: Policy[] }[]>(() => {
    const amountValue = this.data()?.policyNumber;
    const allPolicies = this.insuredPolicies();
    const isOwn = allPolicies.filter((p) => p.policyNumber === amountValue);
    const otherPolicies = allPolicies.filter((p) => p.policyNumber !== amountValue);
    return [
      ...(isOwn.length > 0 ? [{ heading: 'Póliza del expediente', policies: isOwn }] : []),
      ...(otherPolicies.length > 0
        ? [
            {
              heading: isOwn.length > 0 ? 'Sus otras pólizas' : 'Sus pólizas',
              policies: otherPolicies,
            },
          ]
        : []),
    ];
  });

  private readonly openPolicy = signal<string | null>(null);

  protected isPolicyOpen(p: Policy): boolean {
    return this.openPolicy() === p.policyNumber;
  }

  protected togglePolicy(p: Policy): void {
    this.openPolicy.update((isExpanded) => (isExpanded === p.policyNumber ? null : p.policyNumber));
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
            { label: 'Suma asegurada', value: s ? this.formatAmount(s.sumInsured) : null },
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
              value: s?.totalAmountClaimed != null ? this.formatAmount(s.totalAmountClaimed) : null,
            },
          ],
        },
      ];
    },
  );

  /** Policies count even though they arrive later, from their own request. */
  protected readonly hasInsuredData = computed(
    () => this.insuredPolicies().length > 0 || this.fraudRecords().length > 0,
  );

  protected validityPeriod(p: Policy): string {
    return `${formatDate(p.effectiveFrom)} — ${formatDate(p.effectiveTo)}`;
  }

  protected moneyOrNull(value: number | null): string | null {
    return value == null ? null : this.formatAmount(value);
  }

  protected readonly history = computed<StatusTransition[]>(() => this.data()?.statusHistory ?? []);

  protected readonly hasImageAnalysis = computed(
    () => (this.data()?.forensicReport?.findings?.length ?? 0) > 0,
  );

  /** Medium or high findings only; flagging "low" ones would turn the dot into noise. */
  private readonly hasImageMatches = computed(() =>
    (this.data()?.forensicReport?.findings ?? []).some((f) => {
      const level = forensicAlertLevel(f);
      return level === 'medio' || level === 'alto';
    }),
  );

  // Conditional tabs appear only once something has run (referral, classification, forensics).
  protected readonly tabs = computed<
    {
      id: TabId;
      label: string;
      dot?: boolean;
      dotLabel?: string;
      count?: string;
    }[]
  >(() => [
    { id: 'summary' as TabId, label: 'Resumen' },
    ...(this.hasAnalysis()
      ? [
          {
            id: 'analysis' as TabId,
            label: 'Análisis',
            count:
              this.analysisReasons().length > 0 ? `${this.analysisReasons().length}` : undefined,
          },
        ]
      : []),
    ...(this.hasRules() ? [{ id: 'rules' as TabId, label: 'Evaluación de reglas' }] : []),
    { id: 'documents' as TabId, label: 'Documentación' },
    ...(this.hasImageAnalysis()
      ? [
          {
            id: 'images' as TabId,
            label: 'Imágenes',
            dot: this.hasImageMatches(),
            dotLabel: 'con coincidencias de imagen',
          },
        ]
      : []),
    ...(this.hasInsuredData() ? [{ id: 'insured' as TabId, label: 'Asegurado' }] : []),
    ...(this.derivations().length > 0
      ? [{ id: 'assessment' as TabId, label: this.derivationsTabLabel() }]
      : []),
    { id: 'history' as TabId, label: 'Historial' },
  ]);

  /** Fetched apart: counting per row in the shared `CaseResponse` would cost a query per inbox case. */
  protected readonly unreadMessages = signal(0);
  protected readonly chatOpen = signal(false);

  protected readonly quickReplies = computed(() =>
    analystQuickReplies(
      this.data()?.insuredName ?? '',
      this.needsDocs() ? this.missingDocLabels() : [],
    ),
  );
  private readonly selectedTab = signal<TabId>('summary');

  /**
   * Falls back when the selected tab disappears with the data (e.g. a reclassification drops the
   * image analysis).
   */
  protected readonly activeTab = computed<TabId>(() => {
    const selected = this.selectedTab();
    return this.tabs().some((t) => t.id === selected) ? selected : 'summary';
  });

  setTab(t: TabId): void {
    this.selectedTab.set(t);
  }

  protected onAlert(briefAlert: BriefAlert): void {
    if (briefAlert.tab === 'chat') {
      this.chatOpen.set(true);
    } else {
      this.setTab(briefAlert.tab);
    }
  }

  /** The tabs sit below the fold on mobile: without the scroll the click would look like a no-op. */
  protected viewAnalysis(tabs: HTMLElement): void {
    this.setTab('analysis');
    tabs.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  private readonly verbLabels: Record<Verb, string> = {
    approve: 'Aprobar',
    reject: 'Rechazar',
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
    this.pendingDecision() === 'approve'
      ? 'Aprobar y determinar el monto a pagar'
      : 'Justificar decisión: Rechazar',
  );

  protected readonly confirmDisabled = computed(
    () =>
      this.decisionSaving() ||
      !this.justification().trim() ||
      (this.pendingDecision() === 'approve' &&
        (this.recalculoPendiente() || this.approvalBlockedReason() !== null)),
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
    if (v === 'approve') {
      this.precargarMontoDeProveedor();
    }
    this.showJustify.set(true);
  }
  cancelDecision(): void {
    this.showJustify.set(false);
    this.pendingDecision.set(null);
    // A value tried in the dialog must not keep driving the page's amount and warnings.
    this.replacementInput.set('');
    this.replacementApplied.set(null);
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
      decision: verb === 'approve' ? 'APPROVE' : 'REJECT',
      justification: this.justification().trim(),
      // The backend rejects a settlement on a rejection.
      settlement:
        verb === 'approve'
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

  // The backend computes it and explains it line by line; the analyst confirms or adjusts it with a
  // justification.

  protected readonly replacementInput = signal('');
  /** The value last sent to the backend; changing it refetches the proposal. */
  private readonly replacementApplied = signal<number | null>(null);

  /**
   * Always fetched, since resolved cases show it too. A 403 (insured) yields null and the card
   * hides it.
   */
  private readonly settlementFetch = toSignal(
    combineLatest([
      this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
      toObservable(this.reloadTrigger),
      toObservable(this.replacementApplied),
    ]).pipe(
      switchMap(([id, , replacementValue]) =>
        this.service.settlement(id as unknown as number, replacementValue).pipe(
          catchError(() => of<Settlement | null>(null)),
          // Tagged with what it was computed from: the signal keeps the previous answer while the
          // next one loads, so the applied value alone can't tell a draft from the saved proposal.
          map((settlement) => ({ settlement, draft: replacementValue != null })),
        ),
      ),
    ),
    { initialValue: { settlement: null as Settlement | null, draft: false } },
  );

  /** What the approval dialog works on: it follows the amount being tried there. */
  protected readonly settlement = computed(() => this.settlementFetch().settlement);

  /**
   * What the page shows. It ignores the amount tried in the dialog, which is nothing until
   * confirmed: the card behind must not announce a payout the analyst is still working out.
   */
  protected readonly settlementEnPagina = linkedSignal<
    { settlement: Settlement | null; draft: boolean },
    Settlement | null
  >({
    source: this.settlementFetch,
    computation: (fetch, previous) => (fetch.draft ? (previous?.value ?? null) : fetch.settlement),
  });

  /** Signed by the analyst and awaiting the supervisor: the decision buttons give way to a notice. */
  protected readonly awaitingAuthorization = computed(
    () => this.settlement()?.status === 'PENDING_AUTHORIZATION',
  );

  protected readonly settlementReturned = computed(() => this.settlement()?.status === 'RETURNED');

  /** Computed on the proposal, so the analyst learns before confirming that it needs sign-off. */
  protected readonly needsReferent = computed(() => {
    const limit = this.settlement()?.authorityLimit;
    const amount = this.amountToAuthorize();
    return limit != null && amount != null && amount > limit;
  });

  /**
   * REPAIR: it is the calculation base. TOTAL_LOSS: only matters when the coverage settles by the
   * lesser of sum insured and replacement value.
   */
  protected readonly needsAccreditedAmount = computed(() => {
    const s = this.settlement();
    return s?.formula === 'REPAIR' || s?.settlementBasis === 'LESSER_OF_SUM_AND_REPLACEMENT';
  });

  /**
   * A total loss settled by the lesser of the two: today's replacement value is the base of the
   * amount, so approval waits for it. Never defaulted to the sum insured.
   */
  protected readonly needsReplacementValue = computed(() => {
    const s = this.settlement();
    return s?.formula === 'TOTAL_LOSS' && s.settlementBasis === 'LESSER_OF_SUM_AND_REPLACEMENT';
  });

  /**
   * Offered only until a value is entered. A document amount was read by the model, so it waits
   * for the click; a provider's is prefilled by `precargarMontoDeProveedor`.
   */
  protected readonly suggestionAvailable = computed(() => {
    const s = this.settlement();
    return (
      this.needsAccreditedAmount() &&
      s?.suggestedFor === 'ACCREDITED_AMOUNT' &&
      s?.suggestedAmount != null &&
      this.replacementInput().trim() === '' &&
      this.replacementApplied() == null
    );
  });

  /**
   * The repair shop's quote and the expert's valuation were typed in by an analyst when the report
   * came back, so asking to take them again is a click that checks nothing. Still editable.
   */
  private precargarMontoDeProveedor(): void {
    const s = this.settlement();
    const deProveedor =
      s?.suggestedFrom === 'repair_report' || s?.suggestedFrom === 'expert_report';
    if (this.needsAccreditedAmount() && s?.suggestedFor === 'ACCREDITED_AMOUNT' && deProveedor) {
      this.takeSuggestion();
    }
  }

  protected takeSuggestion(): void {
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
  protected readonly amountSuggestionAvailable = computed(() => {
    const s = this.settlement();
    return (
      s?.suggestedFor === 'SETTLED_AMOUNT' &&
      s?.suggestedAmount != null &&
      this.settledAmountInput().trim() === ''
    );
  });

  /** Taking it fills the analyst's amount, so it counts as an adjustment and needs a justification. */
  protected takeAmountSuggestion(): void {
    const amount = this.settlement()?.suggestedAmount;
    if (amount == null) {
      return;
    }
    this.settledAmountInput.set(String(amount));
  }

  private applyReplacementValue(): void {
    this.replacementApplied.set(this.replacementTyped());
  }

  private readonly replacementTyped = computed<number | null>(() => {
    const raw = this.replacementInput().trim();
    return raw === '' ? null : Number(raw);
  });

  /**
   * The breakdown still shows the previous value: what's typed hasn't reached the backend yet.
   * Blocks confirming so nobody authorizes an amount computed from a figure they already changed.
   */
  protected readonly recalculoPendiente = computed(
    () => this.replacementTyped() !== this.replacementApplied(),
  );

  /** Recalculates on its own once the analyst stops typing; a button was a step easy to forget. */
  private readonly autoRecalculo = toObservable(this.replacementInput)
    .pipe(debounceTime(500), takeUntilDestroyed())
    .subscribe(() => this.applyReplacementValue());

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
    if (this.needsReplacementValue() && this.replacementApplied() == null) {
      return 'Cargá cuánto cuesta hoy reponer el bien.';
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

  // Same actions as the authorizations screen, so the supervisor can sign from the case itself.
  protected readonly canAuthorize = computed(
    () => this.session.session()?.rol === 'REFERENTE_ASEGURADORA' && this.awaitingAuthorization(),
  );
  protected readonly showAuthorize = signal(false);
  protected readonly showReturn = signal(false);
  protected readonly returnReason = signal('');
  protected readonly signing = signal(false);
  protected readonly signError = signal<string | null>(null);

  askAuthorize(): void {
    this.signError.set(null);
    this.showAuthorize.set(true);
  }

  askReturn(): void {
    this.returnReason.set('');
    this.signError.set(null);
    this.showReturn.set(true);
  }

  cancelSign(): void {
    this.showAuthorize.set(false);
    this.showReturn.set(false);
  }

  confirmAuthorize(): void {
    const d = this.data();
    if (!d) {
      return;
    }
    this.sign(this.settlementAuthorities.authorize(d.id), 'No se pudo autorizar la liquidación.');
  }

  confirmReturn(): void {
    const d = this.data();
    const reason = this.returnReason().trim();
    if (!d || !reason) {
      return;
    }
    this.sign(
      this.settlementAuthorities.returnToAnalyst(d.id, reason),
      'No se pudo devolver la liquidación.',
    );
  }

  private sign(action: Observable<unknown>, fallbackError: string): void {
    this.signing.set(true);
    this.signError.set(null);
    action.subscribe({
      next: () => {
        this.signing.set(false);
        this.cancelSign();
        this.reloadTrigger.update((v) => v + 1);
      },
      error: (err: HttpErrorResponse) => {
        this.signing.set(false);
        this.cancelSign();
        this.signError.set(err.error?.detail || fallbackError);
      },
    });
  }

  // Shared with the supervisor, like assigning: reopening resolves nothing.
  protected readonly showReopen = signal(false);
  protected readonly reopenReason = signal('');
  protected readonly reopening = signal(false);
  protected readonly reopenError = signal<string | null>(null);

  protected readonly canReopen = computed(
    () => this.canManage() && isFinalStatus(this.data()?.status ?? ''),
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

  // Not a verdict: it suspends the case to gather evidence, hence its own endpoint instead of
  // /decision.
  protected readonly derived = computed(() => this.data()?.status === 'PENDING_EXPERT_REPORT');
  protected readonly inRepair = computed(() => this.data()?.status === 'PENDING_REPAIR');

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
            .pipe(catchError(() => of<DerivationOptions | null>(null))),
        ),
      ),
      { initialValue: null as DerivationOptions | null },
    );
  }

  protected readonly derivations = toSignal(
    combineLatest([
      this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
      toObservable(this.reloadTrigger),
    ]).pipe(
      switchMap(([id]) =>
        this.service
          .derivations(id as unknown as number)
          .pipe(catchError(() => of<Derivacion[]>([]))),
      ),
    ),
    { initialValue: [] as Derivacion[] },
  );

  protected readonly expertDerivation = computed(
    () => this.derivations().find((d) => d.providerType === 'ESTUDIO_LIQUIDADOR') ?? null,
  );
  private readonly repairDerivation = computed(
    () => this.derivations().find((d) => d.providerType === 'SERVICIO_TECNICO') ?? null,
  );

  protected readonly derivationsTabLabel = computed(() => {
    if (this.expertDerivation() && this.repairDerivation()) {
      return 'Derivaciones';
    }
    return this.expertDerivation() ? 'Peritaje' : 'Servicio técnico';
  });

  /** Owner only, like deciding; the backend enforces it (409 unassigned, 403 someone else's). */
  protected readonly canDerive = computed(() => this.withAnalyst() && !this.expertDerivation());

  /**
   * Owner, pending, and not waiting on the referente: once the amount is sent for authorization
   * the analyst already decided, so deriving or reading "si aprobás" hints no longer applies.
   */
  private readonly withAnalyst = computed(
    () => this.canDecide() && this.decisionState() === 'pending' && !this.awaitingAuthorization(),
  );

  /**
   * Owner only, like `puedeDerivar`. `eligible` is true only when the cause admits repair and there is a
   * shop to send it to; null while loading or on error, so the button stays hidden.
   */
  protected readonly canDeriveToRepair = computed(
    () => this.withAnalyst() && !this.repairDerivation() && this.repairOptions()?.eligible === true,
  );

  /** Enabled by the insurer's rule AND with experts to send it to. */
  protected readonly derivationEnabled = computed(
    () => this.derivationOptions()?.eligible === true,
  );

  /**
   * Suggested for high or critical risk bands (deterministic, not a sixth classification); it only
   * highlights the button.
   */
  protected readonly suggestedDerivation = computed(() => {
    const band = this.data()?.riskBand;
    return (band === 'HIGH' || band === 'CRITICAL') && this.derivationEnabled();
  });

  protected readonly derivationSuggestion = computed<string | null>(() => {
    if (!this.suggestedDerivation()) {
      return null;
    }
    const band = this.data()?.riskBand as RiskBand;
    return `Riesgo ${riskBandLabel(band).toLowerCase()}: se sugiere derivar a peritaje antes de decidir.`;
  });

  protected readonly derivationChoices = computed<{ providerType: ProviderType; label: string }[]>(
    () => [
      ...(this.canDerive() && this.derivationEnabled()
        ? [{ providerType: 'ESTUDIO_LIQUIDADOR' as ProviderType, label: 'Peritaje' }]
        : []),
      ...(this.canDeriveToRepair()
        ? [{ providerType: 'SERVICIO_TECNICO' as ProviderType, label: 'Servicio técnico' }]
        : []),
    ],
  );

  /** The calculated amount exceeds the branch limit: approving needs the supervisor's sign-off. */
  protected readonly exceedsAuthority = computed(() => {
    const s = this.settlementEnPagina();
    return this.withAnalyst() && s?.authorityLimit != null && s.calculatedAmount > s.authorityLimit;
  });

  protected readonly hasWarnings = computed(
    () =>
      this.showCauseConsistency() ||
      (this.canAct() && this.settlementReturned() && this.settlement() != null) ||
      this.exceedsAuthority() ||
      (this.canDerive() && !!this.derivationSuggestion()),
  );

  // Brings existing evidence next to the buttons; adds no criteria and suggests nothing.

  protected readonly deadlineText = computed(() => {
    const d = this.data();
    if (!d?.responseDeadline) {
      return null;
    }
    const dateLabel = formatDate(d.responseDeadline);
    return isDeadlinePrioritized(d.deadlinePriority)
      ? `${deadlinePriorityLabel(d.deadlinePriority, d.responseDeadline)} · ${dateLabel}`
      : `Vence el ${dateLabel}`;
  });

  protected readonly deadlineTone = computed<StatusTone>(() => {
    const d = this.data();
    return d && isDeadlinePrioritized(d.deadlinePriority)
      ? deadlinePriorityTone(d.deadlinePriority)
      : 'neutral';
  });

  protected readonly answeredDerivations = computed(() =>
    this.derivations().filter((p) => p.verdict || p.repairOutcome),
  );

  protected derivationResult(p: Derivacion): string {
    const isRepairDerivation = p.providerType === 'SERVICIO_TECNICO';
    const outcome = p.verdict ? verdictLabel(p.verdict) : repairOutcomeLabel(p.repairOutcome ?? '');
    const parsedAmount = isRepairDerivation ? p.repairCost : p.indemnifiableAmount;
    // A repair outcome already names its source; the prefix made the badge overflow the card.
    const texto = isRepairDerivation ? outcome : `Perito: ${outcome}`;
    return parsedAmount != null ? `${texto} · ${this.formatAmount(parsedAmount)}` : texto;
  }

  protected derivationResultTone(p: Derivacion): StatusTone {
    return p.verdict ? verdictTone(p.verdict) : 'neutral';
  }

  /** Signals that pull toward a closer look, each one pointing at the tab with its evidence. */
  protected readonly decisionAlerts = computed<BriefAlert[]>(() => {
    const alertList: BriefAlert[] = [];
    const failedFindings = this.failedRules();
    if (failedFindings > 0) {
      alertList.push({
        label:
          failedFindings === 1 ? '1 regla no cumplida' : `${failedFindings} reglas no cumplidas`,
        tab: 'rules',
      });
    }
    if (this.hasImageMatches()) {
      alertList.push({ label: 'Imágenes con coincidencias', tab: 'images' });
    }
    const priorCount = this.priorFraudRecords().length;
    if (priorCount > 0) {
      alertList.push({
        label:
          priorCount === 1 ? '1 antecedente de fraude' : `${priorCount} antecedentes de fraude`,
        tab: 'insured',
      });
    }
    if (this.unreadMessages() > 0) {
      alertList.push({ label: 'Mensajes sin leer del asegurado', tab: 'chat' });
    }
    return alertList;
  });

  /** Tells the analyst whether it is company policy or missing providers. */
  protected readonly notDerivableReason = computed<string | null>(() => {
    const options = this.derivationOptions();
    if (!options || options.eligible) {
      return null;
    }
    if (options.minClaimedAmount == null) {
      return 'Esta aseguradora no deriva a peritaje los siniestros de este ramo.';
    }
    if (options.providers.length === 0) {
      return 'No hay peritos cargados para este ramo.';
    }
    // Names the expert assessment explicitly so it isn't read as blocking the repair shop too.
    return (
      `El monto reclamado no alcanza el mínimo para derivar a peritaje ` +
      `(${this.formatAmount(options.minClaimedAmount)}).`
    );
  });

  /** Only the missing catalog entry: a claim cause that doesn't go to repair needs no notice. */
  protected readonly motivoSinServicioTecnico = computed<string | null>(() => {
    const options = this.repairOptions();
    if (!this.withAnalyst() || this.repairDerivation()) {
      return null;
    }
    if (!options || options.eligible || !options.allowedByRule) {
      return null;
    }
    return 'No hay servicios técnicos cargados para este ramo.';
  });

  protected readonly derivationType = signal<ProviderType>('ESTUDIO_LIQUIDADOR');
  protected readonly isRepairReferral = computed(
    () => this.derivationType() === 'SERVICIO_TECNICO',
  );

  protected readonly proveedorOptions = computed<SelectOption[]>(() =>
    (
      (this.isRepairReferral() ? this.repairOptions() : this.derivationOptions())?.providers ?? []
    ).map((provider) => ({
      value: String(provider.id),
      // Branches tell specialist from generalist; area matters because the device must be
      // inspected in person.
      label: [provider.name, ramosLabel(provider.branches), provider.zone]
        .filter(Boolean)
        .join(' · '),
    })),
  );

  protected readonly showDerivar = signal(false);
  protected readonly proveedorElegido = signal('');
  protected readonly derivationReason = signal('');
  protected readonly deriveSaving = signal(false);
  protected readonly deriveError = signal<string | null>(null);

  askDerive(providerKind: ProviderType = 'ESTUDIO_LIQUIDADOR'): void {
    this.derivationType.set(providerKind);
    this.proveedorElegido.set('');
    this.derivationReason.set('');
    this.deriveError.set(null);
    this.showDerivar.set(true);
  }

  cancelDerive(): void {
    this.showDerivar.set(false);
  }

  confirmDerive(): void {
    const d = this.data();
    const proveedor = this.proveedorElegido();
    const reasonText = this.derivationReason().trim();
    if (!d || !proveedor || !reasonText) {
      return;
    }
    this.deriveSaving.set(true);
    this.deriveError.set(null);
    this.service.derive(d.id, Number(proveedor), reasonText, this.derivationType()).subscribe({
      next: (assessment) => {
        this.deriveSaving.set(false);
        this.showDerivar.set(false);
        this.derivationDoneCaseId.set(d.id);
        this.derivationDone.set(assessment);
        this.reloadTrigger.update((v) => v + 1);
      },
      error: (err: HttpErrorResponse) => {
        this.deriveSaving.set(false);
        this.deriveError.set(err.error?.detail || 'No se pudo derivar el expediente');
      },
    });
  }

  /**
   * Only while the owner reviews it: the backend refuses any other status and a settlement awaiting
   * the referente, and this keeps the button from offering what it would refuse.
   */
  protected readonly canCorrectCause = computed(
    () => this.withAnalyst() && this.data()?.status === 'PENDING_ANALYST_REVIEW',
  );

  protected readonly showCorrectCause = signal(false);
  protected readonly causeOptions = signal<ClaimCauseOption[] | null>(null);
  protected readonly chosenCause = signal('');
  protected readonly correctionReason = signal('');
  protected readonly correctSaving = signal(false);
  protected readonly correctError = signal<string | null>(null);

  protected readonly causeSelectOptions = computed<SelectOption[]>(() =>
    (this.causeOptions() ?? []).map((o) => ({
      value: String(o.id),
      label: `${o.name} · ${o.coverageName}`,
    })),
  );

  askCorrectCause(): void {
    const d = this.data();
    if (!d) {
      return;
    }
    this.causeOptions.set(null);
    this.chosenCause.set('');
    this.correctionReason.set('');
    this.correctError.set(null);
    this.showCorrectCause.set(true);
    this.service.claimCauseOptions(d.id).subscribe({
      next: (choices) => {
        this.causeOptions.set(choices);
        // What the narrative suggests comes preselected; the analyst still confirms it.
        const suggested = choices.find((o) => o.name === d.suggestedClaimCause);
        if (suggested) {
          this.chosenCause.set(String(suggested.id));
        }
      },
      error: () => {
        this.causeOptions.set([]);
        this.correctError.set('No se pudieron cargar los hechos generadores.');
      },
    });
  }

  cancelCorrectCause(): void {
    this.showCorrectCause.set(false);
  }

  confirmCorrectCause(): void {
    const d = this.data();
    const chosenCauseOption = this.chosenCause();
    const reasonText = this.correctionReason().trim();
    if (!d || !chosenCauseOption || !reasonText) {
      return;
    }
    this.correctSaving.set(true);
    this.correctError.set(null);
    this.service.correctClaimCause(d.id, Number(chosenCauseOption), reasonText).subscribe({
      next: () => {
        this.correctSaving.set(false);
        this.showCorrectCause.set(false);
        this.reloadTrigger.update((v) => v + 1);
      },
      error: (err: HttpErrorResponse) => {
        this.correctSaving.set(false);
        this.correctError.set(err.error?.detail || 'No se pudo corregir el hecho generador');
      },
    });
  }

  /** Kept for the confirmation modal: the only signal that the email went out. */
  protected readonly derivationDone = signal<Derivacion | null>(null);
  // Kept apart from data(), which is empty while the case reloads behind the modal.
  protected readonly derivationDoneCaseId = signal<number | null>(null);

  closeDerivationDone(): void {
    this.derivationDone.set(null);
  }

  protected readonly showReport = signal(false);
  protected readonly reportType = signal<ProviderType>('ESTUDIO_LIQUIDADOR');
  protected readonly reportIsRepair = computed(() => this.reportType() === 'SERVICIO_TECNICO');
  protected readonly repairOutcomeOptions: SelectOption[] = REPAIR_OUTCOME_OPTIONS;
  protected readonly verdictInput = signal('');
  protected readonly verdictNoteInput = signal('');
  protected readonly reportFile = signal<File | null>(null);
  protected readonly reportSaving = signal(false);
  protected readonly reportError = signal<string | null>(null);

  /**
   * Saving "fraude confirmado" also records a fraud mark on the person, which can't be removed from
   * the app.
   */
  protected readonly verdictConfirmsFraud = computed(
    () => !this.reportIsRepair() && this.verdictInput() === 'FRAUD_CONFIRMED',
  );

  protected readonly verdictOptions: SelectOption[] = [
    { value: 'FRAUD_CONFIRMED', label: 'Fraude confirmado' },
    { value: 'FRAUD_DISCARDED', label: 'Fraude descartado' },
    { value: 'INCONCLUSIVE', label: 'No concluyente' },
  ];

  askReport(providerKind: ProviderType = 'ESTUDIO_LIQUIDADOR'): void {
    this.reportType.set(providerKind);
    this.verdictInput.set('');
    this.verdictNoteInput.set('');
    this.reportAmount.set('');
    this.reportFile.set(null);
    this.reportError.set(null);
    this.showReport.set(true);
  }

  cancelReport(): void {
    this.showReport.set(false);
  }

  /**
   * Expert: the determined claim value; repair shop: the repair cost. Label, requiredness and target
   * column differ accordingly.
   */
  protected readonly reportAmount = signal('');

  /** Not asked for IRREPARABLE (the backend rejects it); always offered to the expert. */
  protected readonly needsReportAmount = computed(
    () =>
      !this.reportIsRepair() ||
      this.verdictInput() === 'QUOTE_SENT' ||
      this.verdictInput() === 'REPAIRED',
  );

  /** Already repaired and billed: the amount is an invoice, not a quote. */
  protected readonly reportIsInvoice = computed(
    () => this.reportIsRepair() && this.verdictInput() === 'REPAIRED',
  );

  /** A quote needs an amount; the invoice may come later and the expert's amount is optional. */
  protected readonly reportAmountRequired = computed(
    () => this.reportIsRepair() && this.verdictInput() === 'QUOTE_SENT',
  );

  protected readonly reportAmountMissing = computed(
    () => this.reportAmountRequired() && this.reportAmountNumber() == null,
  );

  private reportAmountNumber(): number | null {
    if (!this.needsReportAmount()) {
      return null;
    }
    const raw = this.reportAmount().trim();
    if (raw === '') {
      return null;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
  }

  onReportFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.reportFile.set(input.files?.[0] ?? null);
  }

  confirmReport(): void {
    const d = this.data();
    const file = this.reportFile();
    const result = this.verdictInput();
    if (!d || !file || !result || this.reportAmountMissing()) {
      return;
    }
    this.reportSaving.set(true);
    this.reportError.set(null);
    const note = this.verdictNoteInput().trim();
    const parsedAmount = this.reportAmountNumber();
    const request = this.reportIsRepair()
      ? this.service.uploadRepairResponse(d.id, result as RepairOutcome, note, parsedAmount, file)
      : this.service.uploadExpertReport(d.id, result as ExpertVerdict, note, parsedAmount, file);
    request.subscribe({
      next: () => {
        this.reportSaving.set(false);
        this.showReport.set(false);
        this.reloadTrigger.update((v) => v + 1);
      },
      error: (err: HttpErrorResponse) => {
        this.reportSaving.set(false);
        this.reportError.set(err.error?.detail || 'No se pudo cargar el informe');
      },
    });
  }

  // Separate from the expert report: carrying a finding over to the person's future claims is the
  // analyst's act, recorded with their name and reason (Ley 25.326).
  private readonly fraudRecords = toSignal(
    combineLatest([
      this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
      toObservable(this.reloadTrigger),
    ]).pipe(
      switchMap(([id]) =>
        this.service
          .fraudRecords(id as unknown as number)
          .pipe(catchError(() => of<FraudRecord[]>([]))),
      ),
    ),
    { initialValue: [] as FraudRecord[] },
  );

  /** From OTHER cases. */
  protected readonly priorFraudRecords = computed(() =>
    this.fraudRecords().filter((a) => a.caseId !== this.data()?.id),
  );

  /** A case yields at most one record. */
  protected readonly ownFraudRecord = computed(
    () => this.fraudRecords().find((a) => a.caseId === this.data()?.id) ?? null,
  );

  /** Analyst only, once, after classification; never on APPROVED. The backend enforces it too. */
  protected readonly canRegisterFraudRecord = computed(() => {
    const status = this.data()?.status;
    return (
      this.canAct() &&
      !this.ownFraudRecord() &&
      (status === 'PENDING_ANALYST_REVIEW' || status === 'REJECTED')
    );
  });

  protected readonly fraudRecordVisible = computed(
    () =>
      this.priorFraudRecords().length > 0 ||
      this.ownFraudRecord() != null ||
      this.canRegisterFraudRecord(),
  );

  /** Defaults to expert-backed when fraud was confirmed. */
  private readonly assessmentConfirmsFraud = computed(
    () => this.expertDerivation()?.verdict === 'FRAUD_CONFIRMED',
  );

  /** Not offered without a confirmed expert verdict: the backend validates against the saved one. */
  protected readonly originOptions = computed<SelectOption[]>(() => [
    ...(this.assessmentConfirmsFraud()
      ? [{ value: 'EXPERT_BACKED', label: fraudRecordOriginLabel('EXPERT_BACKED') }]
      : []),
    { value: 'ANALYST_DECLARED', label: fraudRecordOriginLabel('ANALYST_DECLARED') },
  ]);

  protected readonly showAntecedente = signal(false);
  protected readonly chosenOrigin = signal('');
  protected readonly fraudRecordReason = signal('');
  protected readonly fraudRecordSaving = signal(false);
  protected readonly fraudRecordError = signal<string | null>(null);

  protected readonly fraudRecordReasonMin = FRAUD_RECORD_REASON_MIN;

  /** Warned while typing, not on the 400. */
  protected readonly fraudRecordReasonTooShort = computed(
    () => this.fraudRecordReason().trim().length < FRAUD_RECORD_REASON_MIN,
  );

  protected readonly chosenOriginEffect = computed(() =>
    this.chosenOrigin() === 'EXPERT_BACKED'
      ? 'Va a sumar al nivel de riesgo de sus próximas denuncias y, si la aseguradora lo configuró así, a impedirles la vía rápida.'
      : 'Va a aparecer como alerta en sus próximas denuncias, pero no suma al nivel de riesgo: sin peritaje detrás, una sospecha que mueve el score termina alimentándose sola.',
  );

  askFraudRecord(): void {
    this.chosenOrigin.set(this.assessmentConfirmsFraud() ? 'EXPERT_BACKED' : 'ANALYST_DECLARED');
    this.fraudRecordReason.set('');
    this.fraudRecordError.set(null);
    this.showAntecedente.set(true);
  }

  cancelFraudRecord(): void {
    this.showAntecedente.set(false);
  }

  confirmFraudRecord(): void {
    const d = this.data();
    const source = this.chosenOrigin() as FraudRecordOrigin;
    const reason = this.fraudRecordReason().trim();
    if (!d || !source || this.fraudRecordReasonTooShort()) {
      return;
    }
    this.fraudRecordSaving.set(true);
    this.fraudRecordError.set(null);
    this.service.registerFraudRecord(d.id, { source, reason }).subscribe({
      next: () => {
        this.fraudRecordSaving.set(false);
        this.showAntecedente.set(false);
        this.reloadTrigger.update((v) => v + 1);
      },
      error: (err: HttpErrorResponse) => {
        this.fraudRecordSaving.set(false);
        this.fraudRecordError.set(err.error?.detail || 'No se pudo registrar el antecedente');
      },
    });
  }

  fraudRecordStatusLabel = fraudRecordStatusLabel;
  fraudRecordStatusTone = fraudRecordStatusTone;
  fraudRecordEffect = fraudRecordEffect;
  fraudRecordOriginLabel = fraudRecordOriginLabel;

  verdictLabel = verdictLabel;
  verdictTone = verdictTone;
  repairOutcomeLabel = repairOutcomeLabel;
  formatDateTime = formatDateTime;
  chatListStamp = chatListStamp;

  ruleTypeLabel = ruleTypeLabel;
  ruleResultLabel = ruleResultLabel;
  ruleResultTone = ruleResultTone;
  advisoryResultLabel = advisoryResultLabel;
  advisoryResultTone = advisoryResultTone;
  ruleEvaluationText = ruleEvaluationText;

  private formatAmount(amount: number): string {
    return new Intl.NumberFormat('es-AR', {
      style: 'currency',
      currency: 'ARS',
      maximumFractionDigits: 0,
    }).format(amount);
  }

  protected readonly amountInputLabel = amountInputLabel;
  protected readonly amountInputValue = amountInputValue;
  protected readonly amountInputDisplay = amountInputDisplay;

  /** Like {@link formatAmount} but with cents: rounding would show a figure other than the one saved. */
  protected exactAmount(amount: number | null): string {
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
    const assignedId = this.data()?.assignedAnalystId;
    return this.analysts()
      .filter((a) => a.id !== assignedId)
      .map((a) => ({ value: String(a.id), label: `${a.nombre} ${a.apellido}` }));
  });

  /** Per-tenant analyst id, found by email (the session only has the user id). Null for the supervisor. */
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
  protected readonly canManage = computed(() => {
    const userRole = this.session.session()?.rol;
    return userRole === 'ANALISTA_SINIESTROS' || userRole === 'REFERENTE_ASEGURADORA';
  });

  protected readonly assignedName = computed(() => this.data()?.assignedAnalystName ?? null);
  protected readonly isAssigned = computed(() => this.data()?.assignedAnalystId != null);

  protected readonly awaitingDecisionText = computed(() => {
    const assignedAnalyst = this.assignedName();
    return assignedAnalyst
      ? `Espera la decisión de ${assignedAnalyst}. Podés reasignarlo si hace falta.`
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
    const assignedAnalyst = this.assignedName();
    return assignedAnalyst ? `Asignado a ${assignedAnalyst}` : 'Asignado a otro analista';
  });

  protected readonly analystInitials = computed(() => initialsOf(this.assignedName()));
  protected readonly insuredInitials = computed(() => initialsOf(this.data()?.insuredName));
  protected readonly insuredFirstName = computed(
    () => (this.data()?.insuredName ?? '').trim().split(/\s+/)[0] || 'el asegurado',
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

  protected readonly reassignMenuItems = computed<MenuItem[]>(() => [
    ...this.analystMenuItems(),
    { value: 'release', label: 'Liberar', danger: true },
  ]);

  protected onReassignMenu(value: string): void {
    if (value === 'release') {
      this.release();
    } else {
      this.assignTo(value);
    }
  }

  protected readonly deriveMenuItems = computed<MenuItem[]>(() =>
    this.derivationChoices().map((o) => ({ value: o.providerType, label: o.label })),
  );

  protected onDeriveMenu(value: string): void {
    this.askDerive(value as ProviderType);
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

  private runAssignment(request: Observable<CaseResponse>): void {
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

  protected readonly needsDocs = computed(
    () => this.data()?.analysisClassification === 'FALTA_DOCUMENTACION',
  );

  /** "Resolución" only applies when there is or was something to decide. */
  protected readonly decisionCardHeading = computed(() =>
    this.decisionState() === 'not-ready' && this.needsDocs() && !this.derived() && !this.isFailed()
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
