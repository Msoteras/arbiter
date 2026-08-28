import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, combineLatest, finalize, map, Observable, of, startWith, switchMap } from 'rxjs';

import { ExpedienteService, AnalystDecisionRequest } from '../expediente.service';
import { DocumentAgendaService } from '../document-agenda.service';
import { CaseNavigationService } from '../case-navigation.service';
import { AuthSessionService } from '../../../core/auth/auth-session.service';
import { UserAdminService } from '../../../core/auth/user-admin.service';
import {
  DocumentAnalysis,
  ExpedienteResponse,
  RiskBreakdownItem,
  StatusTransition,
} from '../../../core/models/expediente';
import { riskFactorLabel } from '../../../core/models/business-rules';
import { Policy } from '../../../core/models/policy';
import {
  PolicySnapshot,
  RuleResult,
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
import {
  ExpertVerdict,
  OpcionesDerivacion,
  Peritaje,
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
  riskBandEmptyLabel,
} from '../../../core/models/estado';
import { RiskBand, riskBandLabel } from '../../../core/models/risk-band';
import { StatusTone } from '../../../core/models/status-tone';
import { formatDate, formatDateTime } from '../../../core/util/datetime';
import { FraudGaugeComponent } from '../../../shared/ui/fraud-gauge/fraud-gauge.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { StatusTimelineComponent } from '../../../shared/ui/status-timeline/status-timeline.component';
import { ForensicAnalysisComponent } from './forensic-analysis/forensic-analysis.component';
import { CaseDocumentsComponent } from '../case-documents/case-documents.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ModalComponent } from '../../../shared/ui/modal/modal.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { TextareaComponent } from '../../../shared/ui/textarea/textarea.component';
import { MenuButtonComponent, MenuItem } from '../../../shared/ui/menu-button/menu-button.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { fadeInUp, staggerReveal, tabSwitch } from '../../../shared/animations';

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: ExpedienteResponse }
  | { status: 'error'; httpStatus: number };

type DocsState = { status: 'loading' } | { status: 'ok'; list: CaseDocument[] };

type TabId =
  | 'resumen'
  | 'datos'
  | 'imagenes'
  | 'riesgo'
  | 'razones'
  | 'trazabilidad'
  | 'documentacion'
  | 'peritaje'
  | 'conversacion'
  | 'historial';
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
    SelectComponent,
    TextareaComponent,
    MenuButtonComponent,
    InlineLoadingComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [fadeInUp, staggerReveal, tabSwitch],
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

  /**
   * Los motivos detrás de la clasificación — `analysisReasons` en la API, una fila por motivo
   * (espejo de `llm_reason`, no un string armado con join). Vacío en Fast Track/Falta
   * documentación (son resultados del gate de reglas, no del LLM) o sin clasificación todavía;
   * la tab "Razones" se oculta en ese caso (ver `tabs`).
   */
  protected readonly analysisReasons = computed<string[]>(() => this.data()?.analysisReasons ?? []);

  /**
   * Lo que el modelo leyó de cada adjunto (H0031). Vacío mientras no se clasificó, en un Fast
   * Track que no abrió ningún documento, o en expedientes clasificados antes de que esto se
   * persistiera — en los tres casos la tab no aparece (ver `tabs`).
   */
  protected readonly documentAnalyses = computed<DocumentAnalysis[]>(
    () => this.data()?.documentAnalyses ?? [],
  );

  protected documentLabel(type: string): string {
    return documentTypeLabel(type);
  }

  /**
   * Los campos tipados de un documento, ya listos para la grilla. Se arman acá y no en el
   * template para que el orden sea uno solo y "no aplica" salga de un `null` explícito: un campo
   * que el documento no trae NO es una discrepancia, y mezclarlos haría que la pantalla acuse al
   * asegurado por un dato que nadie declaró.
   */
  protected extractedFields(doc: DocumentAnalysis): FieldItem[] {
    return [
      // formatDate y no formatDateTime: el backend lo guarda en una columna DATE, sin hora.
      { label: 'Fecha del documento', value: doc.documentDate ? formatDate(doc.documentDate) : null },
      { label: 'Importe', value: doc.amount == null ? null : `$${doc.amount.toLocaleString()}` },
      { label: 'Bien que nombra', value: doc.itemDescription },
      { label: 'IMEI', value: doc.imei, mono: true },
      { label: 'Damnificado', value: this.affectedPartyLabel(doc.affectedParty) },
    ];
  }

  /**
   * `DESCONOCIDO` no es un dato faltante: es que el documento no dice de quién era el equipo, y
   * en ese caso la regla de grupo familiar directamente no participa. Por eso se muestra como un
   * valor propio y no como "Sin datos".
   */
  private affectedPartyLabel(affectedParty: string): string {
    const labels: Record<string, string> = {
      TITULAR: 'El titular de la póliza',
      FAMILIAR: 'Un familiar',
      TERCERO: 'Un tercero',
      DESCONOCIDO: 'No lo aclara el documento',
    };
    return labels[affectedParty] ?? affectedParty;
  }

  /**
   * Si el resultado lo produjo el LLM o el gate determinístico de reglas.
   *
   * `FAST_TRACK` y `FALTA_DOCUMENTACION` no son recomendaciones del modelo: los decide el motor de
   * reglas (CLAUDE.md decisión #6 — el LLM nunca puede devolver `FAST_TRACK`). Mostrarles
   * "Confianza del modelo: 100%" es engañoso: no hay inferencia detrás, es un chequeo objetivo, y
   * el 100% sale de un valor fijo que pone el backend, no de una medición.
   */
  protected readonly isDeterministicOutcome = computed(() => {
    const c = this.data()?.analysisClassification;
    return c === 'FAST_TRACK' || c === 'FALTA_DOCUMENTACION';
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

  // ----- trazabilidad -----
  protected readonly ruleResults = computed<RuleResult[]>(() => this.data()?.ruleResults ?? []);

  protected readonly policySnapshot = computed<PolicySnapshot | null>(
    () => this.data()?.policySnapshot ?? null,
  );

  /** The others: this claim's policy is already in the summary. */
  protected readonly otrasPolizas = computed<Policy[]>(() => {
    const d = this.data();
    return (d?.insuredPolicies ?? []).filter((p) => p.policyNumber !== d?.policyNumber);
  });

  protected readonly snapshotFields = computed<FieldItem[]>(() => {
    const s = this.policySnapshot();
    return [
      { label: 'N° de póliza', value: s?.externalPolicyNumber ?? null, mono: true },
      { label: 'Suma asegurada', value: s ? this.formatMonto(s.sumInsured) : null },
      { label: 'Vigencia al momento del hecho', value: s ? (s.inForce ? 'Vigente' : 'No vigente') : null },
      { label: 'Estado de pago', value: s ? (s.paymentsUpToDate ? 'Al día' : 'En mora') : null },
      { label: 'Siniestros previos', value: s ? String(s.previousClaims) : null },
      {
        label: 'Monto total reclamado',
        value: s?.totalAmountClaimed != null ? this.formatMonto(s.totalAmountClaimed) : null,
      },
    ];
  });

  protected readonly hayTrazabilidad = computed(
    () =>
      this.ruleResults().length > 0 ||
      this.policySnapshot() != null ||
      this.otrasPolizas().length > 0 ||
      this.antecedentes().length > 0,
  );

  protected vigencia(p: Policy): string {
    return `${formatDate(p.effectiveFrom)} — ${formatDate(p.effectiveTo)}`;
  }

  protected monto(value: number | null): string | null {
    return value == null ? null : this.formatMonto(value);
  }

  // ----- historial de estados (GET /{id} lo trae con timestamps de cada transición) -----
  protected readonly history = computed<StatusTransition[]>(() => this.data()?.statusHistory ?? []);

  // ----- tabs -----
  // "Peritaje" solo existe si el expediente se derivó, y "Razones" solo si el LLM dejó motivos:
  // una solapa vacía en la mayoría de los casos sería ruido, y en ambas la ausencia de datos es
  // el caso esperado (Fast Track/Falta documentación no tienen motivos; no toda derivación pasa).
  // 'conversacion' sigue oculta: no hay entidad de mensajería todavía, así que estaría siempre
  // vacía. No se borró del tipo ni del `@switch` del template, solo de la lista visible, para no
  // perder el lugar ya pensado en la pantalla (ver la historia en el handoff).
  protected readonly tabs = computed<{ id: TabId; label: string }[]>(() => [
    { id: 'resumen' as TabId, label: 'Resumen' },
    ...(this.documentAnalyses().length > 0
      ? [{ id: 'datos' as TabId, label: 'Datos extraídos' }]
      : []),
    { id: 'imagenes' as TabId, label: 'Análisis de imágenes' },
    { id: 'riesgo' as TabId, label: 'Desglose de riesgo' },
    ...(this.analysisReasons().length > 0 ? [{ id: 'razones' as TabId, label: 'Razones' }] : []),
    ...(this.hayTrazabilidad() ? [{ id: 'trazabilidad' as TabId, label: 'Trazabilidad' }] : []),
    { id: 'documentacion' as TabId, label: 'Documentación' },
    ...(this.peritaje() ? [{ id: 'peritaje' as TabId, label: 'Peritaje' }] : []),
    { id: 'historial' as TabId, label: 'Historial' },
  ]);
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

  // ----- derivación a peritaje -----
  // Derivar no es un veredicto: suspende el expediente para conseguir evidencia. Por eso va por
  // su propio endpoint y no por /decision, que es el registro auditable de la resolución.
  // Frente a indicios de fraude, rechazar apoyándose en una sospecha del modelo no alcanza: el
  // rechazo exige una causa de exclusión, y el peritaje es lo que convierte la sospecha en hecho.
  protected readonly derivado = computed(() => this.data()?.status === 'PENDING_EXPERT_REPORT');

  /** Se pide junto con el expediente: sin esto no se sabe si ofrecer el botón ni a quién derivar. */
  private readonly derivationOptions = toSignal(
    combineLatest([
      this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
      toObservable(this.reloadTrigger),
    ]).pipe(
      switchMap(([id]) =>
        this.service.derivationOptions(id as unknown as number).pipe(
          catchError(() => of<OpcionesDerivacion | null>(null)),
        ),
      ),
    ),
    { initialValue: null as OpcionesDerivacion | null },
  );

  /** 404 mientras el expediente no se derivó — es el caso normal, no un error. */
  protected readonly peritaje = toSignal(
    combineLatest([
      this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
      toObservable(this.reloadTrigger),
    ]).pipe(
      switchMap(([id]) =>
        this.service
          .peritaje(id as unknown as number)
          .pipe(catchError(() => of<Peritaje | null>(null))),
      ),
    ),
    { initialValue: null as Peritaje | null },
  );

  protected readonly puedeDerivar = computed(
    () => this.canAct() && this.decisionState() === 'pending' && !this.peritaje(),
  );

  /** Habilitado por la regla de la aseguradora Y con peritos a quien mandarlo. */
  protected readonly derivacionHabilitada = computed(
    () => this.derivationOptions()?.eligible === true,
  );

  /**
   * Con riesgo alto o crítico se sugiere el peritaje. La sugerencia sale del lado determinístico
   * (banda del scoring) y no de una sexta categoría de Classification: la decisión #6 fija cinco.
   * Sigue siendo el analista el que decide — esto solo destaca el botón.
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

  /**
   * Por qué no se puede derivar. Sin esto el botón queda apagado sin explicación, y el analista
   * no tiene forma de saber si es una política de la compañía o que falta cargar peritos.
   */
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
    return `El monto reclamado no alcanza el mínimo para derivar (${this.formatMonto(options.minClaimedAmount)}).`;
  });

  protected readonly peritoOptions = computed<SelectOption[]>(() =>
    (this.derivationOptions()?.firms ?? []).map((firm) => ({
      value: String(firm.id),
      // El ramo distingue al especialista del generalista, y la zona importa porque para peritar
      // un equipo hay que tenerlo delante.
      label: [firm.name, firm.branchName ?? 'todos los ramos', firm.zone]
        .filter(Boolean)
        .join(' · '),
    })),
  );

  protected readonly showDerivar = signal(false);
  protected readonly peritoElegido = signal('');
  protected readonly motivoDerivacion = signal('');
  protected readonly derivarSaving = signal(false);
  protected readonly derivarError = signal<string | null>(null);

  askDerivar(): void {
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
    this.service.derivarAPeritaje(d.id, Number(perito), motivo).subscribe({
      next: (peritaje) => {
        this.derivarSaving.set(false);
        this.showDerivar.set(false);
        this.derivacionHecha.set(peritaje);
        this.reloadTrigger.update((v) => v + 1);
      },
      error: (err: HttpErrorResponse) => {
        this.derivarSaving.set(false);
        this.derivarError.set(err.error?.detail || 'No se pudo derivar el expediente');
      },
    });
  }

  /**
   * El peritaje recién creado, mientras se muestra la confirmación. Cerrar el modal y ya: debajo
   * cambian el estado, las acciones y la solapa de peritaje todas juntas, y sin una confirmación
   * el analista no tiene cómo saber si el mail salió o si el botón no hizo nada.
   */
  protected readonly derivacionHecha = signal<Peritaje | null>(null);

  cerrarDerivacionHecha(): void {
    this.derivacionHecha.set(null);
  }

  // ----- carga del informe del perito -----
  protected readonly showInforme = signal(false);
  protected readonly veredicto = signal('');
  protected readonly notaVeredicto = signal('');
  protected readonly informeFile = signal<File | null>(null);
  protected readonly informeSaving = signal(false);
  protected readonly informeError = signal<string | null>(null);

  /**
   * Guardar "fraude confirmado" no deja solo el informe: registra el antecedente sobre la persona.
   * El modal lo avisa antes, porque el antecedente no tiene baja desde la aplicación.
   */
  protected readonly veredictoConfirmaFraude = computed(() => this.veredicto() === 'FRAUD_CONFIRMED');

  protected readonly veredictoOptions: SelectOption[] = [
    { value: 'FRAUD_CONFIRMED', label: 'Fraude confirmado' },
    { value: 'FRAUD_DISCARDED', label: 'Fraude descartado' },
    { value: 'INCONCLUSIVE', label: 'No concluyente' },
  ];

  askInforme(): void {
    this.veredicto.set('');
    this.notaVeredicto.set('');
    this.informeFile.set(null);
    this.informeError.set(null);
    this.showInforme.set(true);
  }

  cancelInforme(): void {
    this.showInforme.set(false);
  }

  onInformeFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.informeFile.set(input.files?.[0] ?? null);
  }

  confirmInforme(): void {
    const d = this.data();
    const file = this.informeFile();
    const verdict = this.veredicto() as ExpertVerdict;
    if (!d || !file || !verdict) {
      return;
    }
    this.informeSaving.set(true);
    this.informeError.set(null);
    this.service
      .cargarInformePericial(d.id, verdict, this.notaVeredicto().trim(), file)
      .subscribe({
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

  // ----- antecedente de fraude del asegurado -----
  // Registrar el antecedente NO es lo mismo que subir el informe del perito. El perito verifica un
  // hecho de ESTE siniestro; decidir que ese hecho acompañe a la persona en su próxima denuncia es
  // otro acto, y es del analista. Por eso va aparte y queda con su nombre y su motivo: una marca
  // sobre una persona necesita alguien que se haga cargo (Ley 25.326), no un "lo dijo el informe".
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

  /** Los de OTROS expedientes: lo que el analista tiene que saber antes de decidir este. */
  protected readonly antecedentesPrevios = computed(() =>
    this.antecedentes().filter((a) => a.caseId !== this.data()?.id),
  );

  /** El que este expediente ya originó, si lo hay: un expediente da un antecedente, no dos. */
  protected readonly antecedentePropio = computed(
    () => this.antecedentes().find((a) => a.caseId === this.data()?.id) ?? null,
  );

  /**
   * Solo el analista, sobre un expediente que ya tiene la clasificación en la mano (o que ya
   * rechazó), y una sola vez. Desde APROBADO no aparece: pagar el siniestro y marcarlo como fraude
   * se contradicen. El backend lo valida igual — esto es para la pantalla, no es el control.
   */
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

  /** Con peritaje de fraude confirmado, el respaldo pericial es lo que corresponde por defecto. */
  private readonly peritajeConfirmaFraude = computed(
    () => this.peritaje()?.verdict === 'FRAUD_CONFIRMED',
  );

  /**
   * Sin peritaje confirmado la opción pericial ni se ofrece, en vez de ofrecerla y fallar recién en
   * el submit: el backend la valida contra el veredicto guardado, no contra lo que diga el
   * formulario, y un desplegable que acepta algo que después rebota es peor que uno más corto.
   */
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

  /** El mínimo se avisa mientras escribe, no cuando vuelve el 400. */
  protected readonly motivoAntecedenteCorto = computed(
    () => this.motivoAntecedente().trim().length < MOTIVO_ANTECEDENTE_MIN,
  );

  /** Lo que va a pasar con lo que está por registrar, dicho antes de que confirme. */
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
  formatDateTime = formatDateTime;

  ruleTypeLabel = ruleTypeLabel;
  ruleResultLabel = ruleResultLabel;
  ruleResultTone = ruleResultTone;
  ruleEvaluationText = ruleEvaluationText;

  private formatMonto(amount: number): string {
    return new Intl.NumberFormat('es-AR', {
      style: 'currency',
      currency: 'ARS',
      maximumFractionDigits: 0,
    }).format(amount);
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

  // Sin esto, el menú "Asignar a…"/"Reasignar" se puede abrir mientras la lista de analistas
  // todavía está en vuelo y mostrar un panel vacío, como si la aseguradora no tuviera analistas.
  protected readonly analystsLoading = signal(true);

  private readonly analysts = toSignal(
    this.users.listAnalysts().pipe(
      catchError(() => of([])),
      finalize(() => this.analystsLoading.set(false)),
    ),
    { initialValue: [] },
  );

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
   * Decidir, reintentar y cargar el informe de peritaje son del analista. El referente no decide
   * (decisión #5 de CLAUDE.md, y el backend lo rechaza con 403 al resolverlo contra claims_analyst).
   */
  protected readonly canAct = computed(
    () => this.session.session()?.rol === 'ANALISTA_SINIESTROS',
  );

  /**
   * Mover el expediente sin resolverlo: asignar, reasignar, destrabar una clasificación fallida y
   * cargar el informe del perito. El referente las comparte con el analista porque ninguna atribuye
   * una decisión — para eso ve la carga del equipo.
   */
  protected readonly canGestionar = computed(() => {
    const rol = this.session.session()?.rol;
    return rol === 'ANALISTA_SINIESTROS' || rol === 'REFERENTE_ASEGURADORA';
  });

  protected readonly assignedName = computed(() => this.data()?.assignedAnalystName ?? null);
  protected readonly isAssigned = computed(() => this.data()?.assignedAnalystId != null);

  /** Para el referente: la decisión no es suya, pero repartirla sí. */
  protected readonly esperaDecisionTexto = computed(() => {
    const analista = this.assignedName();
    return analista
      ? `Espera la decisión de ${analista}. Podés reasignarlo si hace falta.`
      : 'Espera la decisión de un analista. Asignalo para que alguien lo tome.';
  });

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
   * fromStatus == toStatus y actor ANALYST o REFERENT — el endpoint de asignación admite ambos
   * roles (ver CaseAccessPolicy.currentAssignmentActor / CaseStatusService.recordAssignment). El
   * último de esos hitos, estando asignado, es la asignación actual.
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

  /**
   * "Decisión del analista" solo tiene sentido cuando hay (o hubo) algo que decidir. Mientras el
   * expediente espera que el asegurado suba lo que falta, no hay ninguna decisión pendiente ni
   * tomada — el título mentía sobre qué mostraba la card.
   */
  protected readonly decisionCardHeading = computed(() =>
    this.decisionState() === 'not-ready' && this.needsDocs() && !this.derivado() && !this.isFailed()
      ? 'Estado del expediente'
      : 'Decisión del analista'
  );

  /** La agenda documental es otra llamada al backend: se refresca con el mismo trigger. */
  protected readonly docsReloadToken = computed(() => this.reloadTrigger());

  // Estado explícito (no solo el array) para poder tapar el checklist de "Falta documentación"
  // mientras esta request —independiente de la principal (getById)— todavía está en vuelo: sin
  // esto, `missingDocLabels` computa con `documents()` en [] y por un instante lista TODOS los
  // tipos requeridos como faltantes, aunque ya estén cargados.
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

  /**
   * La agenda REAL del ramo + hecho generador del expediente (la que configuró el referente), o el
   * catálogo completo.
   */
  private readonly requiredDocTypes = toSignal(
    toObservable(computed(() => ({ branch: this.data()?.branch ?? null, claimCause: this.data()?.claimCause ?? null }))).pipe(
      switchMap(({ branch, claimCause }) =>
        branch && claimCause ? this.agenda.slotsForBranch(branch, claimCause) : of(CASE_DOCUMENT_TYPES),
      ),
    ),
    { initialValue: CASE_DOCUMENT_TYPES as readonly CaseDocumentType[] },
  );

  /** Tipos requeridos que todavía no se cargaron — lo que el analista ve como "falta". */
  protected readonly missingDocLabels = computed(() => {
    const present = new Set(this.documents().map((d) => d.type));
    return this.requiredDocTypes().filter((t) => !present.has(t.type)).map((t) => t.label);
  });
}
