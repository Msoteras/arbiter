import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Observable, finalize, forkJoin, of } from 'rxjs';

import {
  Coverage,
  DOCUMENT_TYPES,
  FastTrackConfig,
  RamoRules,
  SettlementBasis,
  SettlementFormula,
} from '../../../core/models/business-rules';
import { BranchOption, BranchesService } from '../branches.service';
import {
  CoverageOption,
  FastTrackConfigDto,
  FastTrackRulesService,
} from '../fast-track-rules.service';
import {
  CoverageDetail,
  CoverageUpsertRequest,
  CoveragesRulesService,
} from '../coverages-rules.service';
import { ClaimCauseOption, CoverageExclusionsService } from '../coverage-exclusions.service';
import {
  HARD_RULE_LABELS,
  HardRule,
  HardRuleType,
  HardRulesService,
  INSURER_HARD_RULE_LABELS,
  InsurerHardRule,
  InsurerHardRuleType,
  OnArrears,
} from '../hard-rules.service';
import { DocumentRulesService } from '../document-rules.service';
import { BusinessRulesTextService } from '../business-rules-text.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { ModalComponent } from '../../../shared/ui/modal/modal.component';
import { AtribucionesConfigComponent } from '../atribuciones-config/atribuciones-config.component';
import { ScoringConfigComponent } from '../scoring-config/scoring-config.component';
import { FraudeConfigComponent } from '../fraude-config/fraude-config.component';
import { ObjetivoConfigComponent } from '../objetivo-config/objetivo-config.component';
import { HistorialReglasComponent } from '../historial-reglas/historial-reglas.component';
import { SaveBarComponent } from '../../../shared/ui/save-bar/save-bar.component';
import { StringListEditorComponent } from './string-list-editor.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { InfoTipComponent } from '../../../shared/ui/info-tip/info-tip.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { SwitchComponent } from '../../../shared/ui/switch/switch.component';
import { TableComponent } from '../../../shared/ui/table/table.component';
import { CheckboxComponent } from '../../../shared/ui/checkbox/checkbox.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { ToastService } from '../../../shared/ui/toast/toast.service';
import { accordion, fadeInUp, listStagger, staggerReveal } from '../../../shared/animations';

type TabId = 'coberturas' | 'exclusiones' | 'fastTrack' | 'documentacion' | 'reglas';

/** Right-panel views that don't depend on the selected branch. */
type GeneralView = 'hardStop' | 'scoring' | 'fraude' | 'atribuciones' | 'objetivo' | 'historial';

/**
 * Branch master-detail with per-tab drafts, each saved by its own button (cases-service for
 * coverages, rules-service for the rest). Branches are a GLOBAL catalog shared by every insurer.
 * Insurer-wide config (Hard Stop, scoring, etc.) lives outside the master-detail on purpose.
 * The UI shows percentages (0..100); the backend contract uses fractions (0..1).
 */
@Component({
  selector: 'app-reglas',
  imports: [
    ButtonComponent,
    CardComponent,
    EmptyStateComponent,
    InputComponent,
    ModalComponent,
    ScoringConfigComponent,
    FraudeConfigComponent,
    AtribucionesConfigComponent,
    ObjetivoConfigComponent,
    HistorialReglasComponent,
    SaveBarComponent,
    StringListEditorComponent,
    InlineLoadingComponent,
    InfoTipComponent,
    BadgeComponent,
    SwitchComponent,
    TableComponent,
    CheckboxComponent,
    SelectComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal, listStagger, fadeInUp, accordion],
  templateUrl: './reglas.component.html',
  // Order matters: the files are concatenated as listed; swapping them changes the cascade.
  styleUrls: ['./reglas.component.scss', './reglas-secciones.scss'],
})
export class ReglasComponent {
  private readonly branchesService = inject(BranchesService);
  private readonly ftService = inject(FastTrackRulesService);
  private readonly coveragesService = inject(CoveragesRulesService);
  private readonly exclusionsService = inject(CoverageExclusionsService);
  private readonly hardRulesService = inject(HardRulesService);
  private readonly documentsService = inject(DocumentRulesService);
  private readonly rulesTextService = inject(BusinessRulesTextService);
  private readonly toastService = inject(ToastService);

  protected readonly ftSaving = signal(false);
  protected readonly ftError = signal<string | null>(null);
  // Fast Track is configured per coverage (insurer_rule.coverage_id): the tab edits one coverage of
  // the branch at a time, and `draft.fastTrack` holds the config of the one picked here.
  protected readonly ftCoverages = signal<CoverageOption[]>([]);
  protected readonly ftCoverageId = signal<number | null>(null);
  /** Loading a coverage picked from the selector (the branch's first load goes through detailLoading). */
  protected readonly ftLoading = signal(false);
  /** The coverage list itself failed: not the same as a branch that has no coverages. */
  protected readonly ftCoveragesFailed = signal(false);
  protected readonly ftCoverageOptions = computed<SelectOption[]>(() =>
    this.ftCoverages().map((c) => ({ value: String(c.id), label: c.name })),
  );
  protected readonly ftCoverageValue = computed(() => {
    const id = this.ftCoverageId();
    return id == null ? '' : String(id);
  });

  protected readonly covSaving = signal(false);
  protected readonly covError = signal<string | null>(null);

  protected readonly exclSaving = signal(false);
  protected readonly exclError = signal<string | null>(null);
  private loadedCoverages: Coverage[] = [];

  protected readonly docSaving = signal(false);
  protected readonly docError = signal<string | null>(null);

  protected readonly rulesSaving = signal(false);
  protected readonly rulesError = signal<string | null>(null);

  protected readonly docTypes = DOCUMENT_TYPES;
  // Claim causes of the selected branch: used by the coverage exclusions and the Documents matrix.
  protected readonly claimCauses = signal<ClaimCauseOption[]>([]);

  // Renaming is the only branch edit exposed here; creating/deleting branches isn't in the UI.
  protected readonly renaming = signal(false);
  protected readonly renameSaved = signal(false);
  protected readonly renameError = signal<string | null>(null);
  /** Renames ask for confirmation: the branch catalog is global, shared by every insurer. */
  protected readonly showRenameConfirm = signal(false);

  protected readonly tabs: { id: TabId; label: string }[] = [
    { id: 'coberturas', label: 'Coberturas' },
    { id: 'exclusiones', label: 'Exclusiones comunes' },
    { id: 'fastTrack', label: 'Fast Track' },
    { id: 'documentacion', label: 'Documentación' },
    { id: 'reglas', label: 'Reglas de negocio' },
  ];
  private static readonly LAST_TAB_KEY = 'arbiter.reglas.lastTab';
  protected readonly activeTab = signal<TabId>(this.loadLastTab());

  protected readonly ramos = signal<RamoRules[]>([]);
  protected readonly ramosLoading = signal(true);
  // Keeps the detail panel from filling in piecemeal while its requests are in flight.
  protected readonly detailLoading = signal(false);
  /** Branch whose detail is loading, so responses for a branch left behind are ignored. */
  private detailToken: string | null = null;
  private pendingDetail = 0;
  protected readonly selectedId = signal<string | null>(null);
  protected readonly view = signal<'ramo' | GeneralView>('ramo');
  protected readonly draft = signal<RamoRules | null>(null);

  /**
   * Last state confirmed by the backend; unsaved changes are measured against it. Synced per slice,
   * not whole: loads land at different times and a full sync would wipe other tabs' pending edits.
   */
  private readonly persisted = signal<RamoRules | null>(null);

  private markPersisted(...fields: (keyof RamoRules)[]): void {
    const current = this.draft();
    if (!current) {
      return;
    }
    this.persisted.update((base) => {
      const next: RamoRules = base ? { ...base } : structuredClone(current);
      for (const field of fields) {
        Object.assign(next, { [field]: structuredClone(current[field]) });
      }
      return next;
    });
  }

  private sliceChanged(...fields: (keyof RamoRules)[]): boolean {
    const current = this.draft();
    const base = this.persisted();
    if (!current || !base) {
      return false;
    }
    return fields.some((field) => JSON.stringify(current[field]) !== JSON.stringify(base[field]));
  }

  private restoreSlice(...fields: (keyof RamoRules)[]): void {
    const base = this.persisted();
    if (!base) {
      return;
    }
    this.draft.update((current) => {
      if (!current) {
        return current;
      }
      const next: RamoRules = { ...current };
      for (const field of fields) {
        Object.assign(next, { [field]: structuredClone(base[field]) });
      }
      return next;
    });
  }

  protected readonly covDirty = computed(() => this.sliceChanged('coverages'));
  protected readonly exclDirty = computed(() => this.sliceChanged('commonExclusions'));
  protected readonly ftDirty = computed(() => this.sliceChanged('fastTrack'));
  protected readonly docDirty = computed(() => this.sliceChanged('requiredDocumentsByClaimCause'));
  protected readonly rulesDirty = computed(() => this.sliceChanged('businessRules'));

  protected discardCoverages(): void {
    this.restoreSlice('coverages');
    this.covError.set(null);
  }

  protected discardCommonExclusions(): void {
    this.restoreSlice('commonExclusions');
    this.exclError.set(null);
  }

  protected discardFastTrack(): void {
    this.restoreSlice('fastTrack');
    this.ftError.set(null);
  }

  protected discardDocuments(): void {
    this.restoreSlice('requiredDocumentsByClaimCause');
    this.docError.set(null);
  }

  protected discardBusinessRules(): void {
    this.restoreSlice('businessRules');
    this.rulesError.set(null);
  }

  // Cached because the counts and the branch list are independent requests with no guaranteed
  // order; both callbacks reapply it, so whichever arrives last wins.
  private coverageCounts = new Map<string, number>();

  constructor() {
    this.branchesService.list().subscribe({
      next: (branches) => {
        const ramos = branches.map((b) => this.shellFromBranch(b));
        this.ramos.set(ramos);
        this.ramosLoading.set(false);
        this.applyCoverageCounts();
        if (ramos.length > 0) {
          this.select(ramos[0]);
        }
      },
      error: () => this.ramosLoading.set(false),
    });
    this.refreshCoverageSummary();
    this.loadInsurerHardRules();
  }

  /** Also called after saving coverages, since adding or removing one changes the count. */
  private refreshCoverageSummary(): void {
    this.coveragesService.summary().subscribe({
      next: (counts) => {
        this.coverageCounts = new Map(counts.map((c) => [String(c.branchId), c.coverageCount]));
        this.applyCoverageCounts();
      },
      error: () => {
        /* best-effort: keep the last known counts */
      },
    });
  }

  private applyCoverageCounts(): void {
    if (this.coverageCounts.size === 0) {
      return;
    }
    this.ramos.update((list) =>
      list.map((r) => ({ ...r, coverageCount: this.coverageCounts.get(r.id) ?? r.coverageCount })),
    );
  }

  private beginDetailLoad(ramoId: string): void {
    this.detailToken = ramoId;
    this.pendingDetail = 0;
    this.detailLoading.set(true);
  }

  /**
   * The loader turns off only when every tracked request finished, including the ones chained off
   * the coverages response. Requests for a branch the user already left don't count.
   */
  private trackDetail<T>(ramoId: string, source: Observable<T>): Observable<T> {
    if (this.detailToken !== ramoId) {
      return source;
    }
    this.pendingDetail += 1;
    return source.pipe(
      finalize(() => {
        if (this.detailToken !== ramoId) {
          return;
        }
        this.pendingDetail -= 1;
        if (this.pendingDetail === 0) {
          this.detailLoading.set(false);
        }
      }),
    );
  }

  /** Turns the loader off when nothing was requested. */
  private settleDetailLoad(): void {
    if (this.pendingDetail === 0) {
      this.detailLoading.set(false);
    }
  }

  /** Only id + name; the rest is loaded from the backend on select. */
  private shellFromBranch(branch: BranchOption): RamoRules {
    return {
      id: String(branch.id),
      name: branch.name,
      coverages: [],
      coverageCount: 0,
      commonExclusions: [],
      requiredDocumentsByClaimCause: {},
      businessRules: [],
      fastTrack: {
        enabled: true,
        minPolicyAgeMonths: null,
        maxPriorClaims: null,
        priorClaimsWindowMonths: null,
        maxClaimedAmountRatio: null,
        requiresUpToDatePolicy: true,
        requiredDocumentTypes: [],
        criteria: [],
      },
    };
  }

  protected isSelected(r: RamoRules): boolean {
    return this.view() === 'ramo' && this.selectedId() === r.id;
  }

  /** Insurer-wide sections, not tied to a branch. */
  protected readonly generalSections: { id: GeneralView; label: string }[] = [
    { id: 'hardStop', label: 'Hard Stop' },
    { id: 'scoring', label: 'Puntaje de riesgo' },
    { id: 'fraude', label: 'Gestión de fraude' },
    // Caps are per branch but listed together so they can be compared at a glance.
    { id: 'atribuciones', label: 'Atribuciones de liquidación' },
    { id: 'objetivo', label: 'Objetivo de resolución' },
    // 'historial' is deliberately not here: it configures nothing and has its own Audit block.
  ];

  protected selectGeneral(section: GeneralView): void {
    this.view.set(section);
  }

  protected select(r: RamoRules): void {
    this.view.set('ramo');
    this.selectedId.set(r.id);
    this.draft.set(structuredClone(r));
    this.persisted.set(structuredClone(r));
    this.expandedCoverageId.set(null);
    this.activeTab.set('coberturas');
    this.ftError.set(null);
    this.ftCoverages.set([]);
    this.ftCoverageId.set(null);
    this.ftLoading.set(false);
    this.ftCoveragesFailed.set(false);
    this.covError.set(null);
    this.exclError.set(null);
    this.docError.set(null);
    this.rulesError.set(null);
    this.renameSaved.set(false);
    this.renameError.set(null);
    this.beginDetailLoad(r.id);
    this.loadClaimCauses(r);
    this.loadFastTrackFromBackend(r);
    this.loadCoveragesFromBackend(r);
    this.loadBusinessRulesFromBackend(r);
    this.settleDetailLoad();
  }

  private loadClaimCauses(r: RamoRules): void {
    this.claimCauses.set([]);
    const branchId = this.branchIdOf(r);
    if (branchId == null) {
      return;
    }
    this.trackDetail(r.id, this.exclusionsService.listClaimCauses(branchId)).subscribe({
      next: (options) => {
        this.claimCauses.set(options);
        // The document agenda is edited as a matrix, so every claim cause's agenda is loaded.
        options.forEach((option) => this.loadDocumentsFromBackend(r, option.id));
      },
      error: () => {
        /* best-effort: the selector stays empty */
      },
    });
  }

  /**
   * Loads the branch's coverages for the Fast Track selector and the persisted config of the first
   * one, overlaid on the draft so the referente sees what's stored. Best-effort: if it fails the
   * draft keeps its defaults. Only for branches with a real branchId.
   */
  private loadFastTrackFromBackend(r: RamoRules): void {
    const branchId = this.branchIdOf(r);
    if (branchId == null) {
      return;
    }
    this.trackDetail(r.id, this.ftService.listCoverages(branchId)).subscribe({
      next: (coverages) => {
        if (this.draft()?.id !== r.id) {
          return;
        }
        this.ftCoverages.set(coverages);
        const first = coverages[0]?.id ?? null;
        this.ftCoverageId.set(first);
        if (first != null) {
          this.loadFastTrackForCoverage(r.id, branchId, first, true);
        }
      },
      error: () => {
        if (this.draft()?.id === r.id) {
          this.ftCoveragesFailed.set(true);
        }
      },
    });
  }

  /**
   * Switching coverage replaces the whole tab's content. With unsaved changes the selector is
   * disabled (see the template), so this never drops an edit silently.
   */
  protected selectFtCoverage(value: string): void {
    const d = this.draft();
    const branchId = d ? this.branchIdOf(d) : null;
    const coverageId = Number(value);
    if (!d || branchId == null || !Number.isInteger(coverageId) || this.ftDirty()) {
      return;
    }
    if (coverageId === this.ftCoverageId()) {
      return;
    }
    this.ftError.set(null);
    this.ftCoverageId.set(coverageId);
    this.loadFastTrackForCoverage(d.id, branchId, coverageId, false);
  }

  private loadFastTrackForCoverage(
    ramoId: string,
    branchId: number,
    coverageId: number,
    initial: boolean,
  ): void {
    const source = this.ftService.getFastTrack(branchId, coverageId);
    if (!initial) {
      this.ftLoading.set(true);
    }
    (initial ? this.trackDetail(ramoId, source) : source).subscribe({
      next: (dto) => {
        // A late answer for a coverage (or branch) the referente already left must not land on
        // the one on screen: that's exactly how one coverage's config ends up saved on another.
        if (this.draft()?.id !== ramoId || this.ftCoverageId() !== coverageId) {
          return;
        }
        this.overlayFastTrack(dto);
        this.ftLoading.set(false);
      },
      error: (e: unknown) => {
        if (this.ftCoverageId() !== coverageId) {
          return;
        }
        this.ftLoading.set(false);
        if (!initial) {
          this.ftError.set(this.backendErrorMessage(e));
        }
      },
    });
  }

  private overlayFastTrack(dto: FastTrackConfigDto | null): void {
    // Fast Track is always on; with no stored config the thresholds are simply empty.
    this.draft.update((d) =>
      d
        ? {
            ...d,
            fastTrack: {
              ...d.fastTrack,
              enabled: true,
              maxClaimedAmountRatio: dto?.maxClaimedAmountRatio ?? null,
              maxPriorClaims: dto?.maxPriorClaims ?? null,
              priorClaimsWindowMonths: dto?.priorClaimsWindowMonths ?? null,
              minPolicyAgeMonths: dto?.minPolicyAgeMonths ?? null,
              requiresUpToDatePolicy:
                dto?.requiresUpToDatePolicy ?? d.fastTrack.requiresUpToDatePolicy,
              requiredDocumentTypes: dto?.requiredDocumentTypes ?? [],
              // With a stored config its criteria win even when empty: empty is a decision.
              criteria: dto ? (dto.criteria ?? []) : d.fastTrack.criteria,
            },
          }
        : d,
    );
    this.markPersisted('fastTrack');
  }

  /** Keeps a copy in `loadedCoverages` to diff creates/updates/deletes on save. */
  private loadCoveragesFromBackend(r: RamoRules): void {
    const branchId = this.branchIdOf(r);
    if (branchId == null) {
      return;
    }
    const ramoId = r.id;
    this.trackDetail(ramoId, this.coveragesService.listDetailed(branchId)).subscribe({
      next: (list) => {
        this.overlayCoverages(ramoId, list);
        this.loadCoverageExclusions(ramoId, list);
        this.loadCoverageHardRules(ramoId, list);
      },
      error: () => {
        /* best-effort */
      },
    });
    this.trackDetail(ramoId, this.rulesTextService.getExclusions(branchId)).subscribe({
      next: (items) => {
        this.draft.update((d) => (d ? { ...d, commonExclusions: items } : d));
        this.markPersisted('commonExclusions');
      },
      error: () => {
        /* best-effort */
      },
    });
  }

  private overlayCoverages(ramoId: string, list: CoverageDetail[]): void {
    const coverages: Coverage[] = list.map((c) => ({
      id: String(c.id),
      name: c.name,
      clause: c.clause ?? '',
      insuredAmount: null,
      deductibleRatio: c.deductibleRatio,
      reportingWindowDays: c.reportingWindowDays,
      maxAnnualClaims: c.maxAnnualClaims,
      waitingPeriodDays: c.waitingPeriodDays,
      coversFamilyGroup: c.coversFamilyGroup,
      claimExhaustsCoverage: c.claimExhaustsCoverage,
      settlementFormula: c.settlementFormula ?? 'TOTAL_LOSS',
      settlementBasis: c.settlementBasis ?? 'SUM_INSURED',
      secondEventRatio: c.secondEventRatio,
      deductPendingInstallments: c.deductPendingInstallments,
      deductOverdueBalance: c.deductOverdueBalance,
      exclusions: c.exclusions ?? [],
      // Filled in by loadCoverageExclusions / loadCoverageHardRules (they live in rules-service).
      excludedClaimCauseIds: [],
      hardRules: [],
    }));
    this.loadedCoverages = coverages;
    // Only if the draft still shows this branch: a slow reply for a branch the user left mustn't
    // clobber the screen.
    this.draft.update((d) => (d && d.id === ramoId ? { ...d, coverages } : d));
    this.markPersisted('coverages');
  }

  /** Best-effort and independent per coverage: if one fails, the others still load. */
  private loadCoverageExclusions(ramoId: string, list: CoverageDetail[]): void {
    list.forEach((c) => {
      this.trackDetail(ramoId, this.exclusionsService.get(c.id)).subscribe({
        next: (ids) => this.setCoverageExcludedIds(String(c.id), ids),
        error: () => {
          /* best-effort */
        },
      });
    });
  }

  private setCoverageExcludedIds(coverageId: string, ids: number[]): void {
    this.draft.update((d) =>
      d
        ? {
            ...d,
            coverages: d.coverages.map((c) =>
              c.id === coverageId ? { ...c, excludedClaimCauseIds: ids } : c,
            ),
          }
        : d,
    );
    // Also update the loaded baseline so the save diff doesn't see it as a change.
    this.loadedCoverages = this.loadedCoverages.map((c) =>
      c.id === coverageId ? { ...c, excludedClaimCauseIds: ids } : c,
    );
    this.markPersisted('coverages');
  }

  /** Best-effort and independent per coverage, like the exclusions. */
  private loadCoverageHardRules(ramoId: string, list: CoverageDetail[]): void {
    const draft = this.draft();
    const branchId = draft ? this.branchIdOf(draft) : null;
    if (branchId == null) {
      return;
    }
    list.forEach((c) => {
      this.trackDetail(ramoId, this.hardRulesService.get(branchId, c.id)).subscribe({
        next: (rules) => this.setCoverageHardRules(String(c.id), rules),
        error: () => {
          /* best-effort */
        },
      });
    });
  }

  private setCoverageHardRules(coverageId: string, rules: HardRule[]): void {
    this.draft.update((d) =>
      d
        ? {
            ...d,
            coverages: d.coverages.map((c) =>
              c.id === coverageId ? { ...c, hardRules: rules } : c,
            ),
          }
        : d,
    );
    this.loadedCoverages = this.loadedCoverages.map((c) =>
      c.id === coverageId ? { ...c, hardRules: rules } : c,
    );
    this.markPersisted('coverages');
  }

  private loadDocumentsFromBackend(r: RamoRules, claimCauseId: number): void {
    const branchId = this.branchIdOf(r);
    if (branchId == null) {
      return;
    }
    this.trackDetail(r.id, this.documentsService.get(branchId, claimCauseId)).subscribe({
      next: (types) => {
        this.draft.update((d) =>
          d
            ? {
                ...d,
                requiredDocumentsByClaimCause: {
                  ...d.requiredDocumentsByClaimCause,
                  [claimCauseId]: types,
                },
              }
            : d,
        );
        this.markPersisted('requiredDocumentsByClaimCause');
      },
      error: () => {
        /* best-effort */
      },
    });
  }

  private loadBusinessRulesFromBackend(r: RamoRules): void {
    const branchId = this.branchIdOf(r);
    if (branchId == null) {
      return;
    }
    this.trackDetail(r.id, this.rulesTextService.getBusinessRules(branchId)).subscribe({
      next: (items) => {
        this.draft.update((d) => (d ? { ...d, businessRules: items } : d));
        this.markPersisted('businessRules');
      },
      error: () => {
        /* best-effort */
      },
    });
  }

  private branchIdOf(r: RamoRules): number | null {
    const n = Number(r.id);
    return Number.isInteger(n) && n > 0 ? n : null;
  }

  protected setTab(t: TabId): void {
    this.activeTab.set(t);
    localStorage.setItem(ReglasComponent.LAST_TAB_KEY, t);
  }

  /** Falls back to Coberturas when nothing valid is stored. */
  private loadLastTab(): TabId {
    const saved = localStorage.getItem(ReglasComponent.LAST_TAB_KEY);
    return this.tabs.some((t) => t.id === saved) ? (saved as TabId) : 'coberturas';
  }

  // ───────────────── Branch rename ─────────────────

  protected setName(name: string): void {
    this.draft.update((d) => (d ? { ...d, name } : d));
    this.renameSaved.set(false);
  }

  /** The saved name: the draft already holds what was typed. */
  protected readonly renameFrom = computed(
    () => this.ramos().find((r) => r.id === this.selectedId())?.name ?? '',
  );

  /** Validates locally first, so the modal never opens just to say the name is empty. */
  protected requestRename(): void {
    const d = this.draft();
    if (!d || this.renaming()) {
      return;
    }
    if (this.branchIdOf(d) == null || d.name.trim() === '') {
      this.renameError.set('El nombre del ramo no puede estar vacío.');
      return;
    }
    if (d.name.trim() === this.renameFrom()) {
      this.renameError.set('El nombre es el mismo que ya tiene el ramo.');
      return;
    }
    this.renameError.set(null);
    this.showRenameConfirm.set(true);
  }

  protected cancelRename(): void {
    this.showRenameConfirm.set(false);
  }

  /** Closes the dialog; saving progress and errors show next to the button, not in the modal. */
  protected confirmRename(): void {
    this.showRenameConfirm.set(false);
    this.saveName();
  }

  private saveName(): void {
    const d = this.draft();
    if (!d || this.renaming()) {
      return;
    }
    const branchId = this.branchIdOf(d);
    const name = d.name.trim();
    if (branchId == null || name === '') {
      this.renameError.set('El nombre del ramo no puede estar vacío.');
      return;
    }
    this.renameError.set(null);
    this.renameSaved.set(false);
    this.renaming.set(true);
    this.branchesService.rename(branchId, name).subscribe({
      next: (branch) => {
        this.renaming.set(false);
        this.renameSaved.set(true);
        this.ramos.update((list) =>
          list.map((r) => (r.id === String(branch.id) ? { ...r, name: branch.name } : r)),
        );
      },
      error: (e: unknown) => {
        this.renaming.set(false);
        this.renameError.set(this.backendErrorMessage(e));
      },
    });
  }

  // ───────────────── Coverages ─────────────────
  protected addCoverage(): void {
    const coverage: Coverage = {
      id: `cov-${Date.now()}`,
      name: '',
      clause: '',
      insuredAmount: null,
      deductibleRatio: null,
      reportingWindowDays: null,
      maxAnnualClaims: null,
      waitingPeriodDays: null,
      coversFamilyGroup: false,
      claimExhaustsCoverage: false,
      // Default: ceiling = sum insured, no deductions beyond the deductible. Enabling deductions
      // changes what the insured gets paid, so it must be an explicit choice.
      settlementFormula: 'TOTAL_LOSS',
      settlementBasis: 'SUM_INSURED',
      secondEventRatio: null,
      deductPendingInstallments: false,
      deductOverdueBalance: false,
      exclusions: [],
      excludedClaimCauseIds: [],
      hardRules: [],
    };
    this.draft.update((d) => (d ? { ...d, coverages: [...d.coverages, coverage] } : d));
    // Opened right away, or adding a collapsed row at the bottom looks like nothing happened.
    this.expandedCoverageId.set(coverage.id);
  }

  // ───────────────── Hard temporal rules per coverage ─────────────────
  /** Empty while loading, or for a coverage not yet saved. */
  protected hardRulesOf(c: Coverage): HardRule[] {
    return c.hardRules ?? [];
  }

  protected hardRuleLabel(type: HardRuleType): string {
    return HARD_RULE_LABELS[type];
  }

  /** Only the police-report deadline has its own threshold; the rest use coverage columns. */
  protected hasOwnThreshold(rule: HardRule): boolean {
    return rule.ruleType === 'POLICE_DEADLINE';
  }

  protected policeDeadlineStr(c: Coverage): string {
    const rule = this.hardRulesOf(c).find((r) => r.ruleType === 'POLICE_DEADLINE');
    return this.intStr(rule?.deadlineHours ?? null);
  }

  protected setPoliceDeadline(coverageId: string, value: string): void {
    this.patchHardRule(coverageId, 'POLICE_DEADLINE', (r) => ({
      ...r,
      deadlineHours: this.intFromStr(value),
    }));
  }

  protected toggleHardRule(coverageId: string, type: HardRuleType): void {
    this.patchHardRule(coverageId, type, (r) => ({ ...r, enabled: !r.enabled }));
  }

  private patchHardRule(
    coverageId: string,
    type: HardRuleType,
    patch: (rule: HardRule) => HardRule,
  ): void {
    this.draft.update((d) =>
      d
        ? {
            ...d,
            coverages: d.coverages.map((c) =>
              c.id === coverageId
                ? {
                    ...c,
                    hardRules: this.hardRulesOf(c).map((r) => (r.ruleType === type ? patch(r) : r)),
                  }
                : c,
            ),
          }
        : d,
    );
  }

  // ───────────────── Hard Stop: insurer-wide rules ─────────────────
  // Policy in force and arrears don't depend on the coverage: loaded once, not per branch.
  protected readonly insurerHardRules = signal<InsurerHardRule[]>([]);
  // `detailLoading` doesn't cover this load; without it the rules would briefly show as inactive.
  protected readonly hardStopLoading = signal(true);
  protected readonly hardStopSaving = signal(false);
  protected readonly hardStopError = signal<string | null>(null);
  /** Last state confirmed by the backend; unsaved changes are measured against it. */
  private readonly persistedHardRules = signal<InsurerHardRule[]>([]);

  protected readonly hardStopDirty = computed(
    () => JSON.stringify(this.insurerHardRules()) !== JSON.stringify(this.persistedHardRules()),
  );

  protected discardHardStop(): void {
    this.insurerHardRules.set(structuredClone(this.persistedHardRules()));
    this.hardStopError.set(null);
  }

  protected readonly onArrearsOptions: SelectOption[] = [
    { value: 'REJECT', label: 'Rechazar en el alta' },
    { value: 'STANDBY', label: 'Permitir el alta y evaluar después' },
  ];

  private loadInsurerHardRules(): void {
    this.hardStopLoading.set(true);
    this.hardRulesService
      .getInsurerWide()
      .pipe(finalize(() => this.hardStopLoading.set(false)))
      .subscribe({
        next: (rules) => {
          this.insurerHardRules.set(rules);
          this.persistedHardRules.set(structuredClone(rules));
        },
        error: () => {
          /* best-effort: the tab stays empty */
        },
      });
  }

  protected insurerHardRuleLabel(type: InsurerHardRuleType): string {
    return INSURER_HARD_RULE_LABELS[type];
  }

  protected toggleInsurerHardRule(type: InsurerHardRuleType): void {
    this.insurerHardRules.update((list) =>
      list.map((r) => (r.ruleType === type ? { ...r, enabled: !r.enabled } : r)),
    );
  }

  protected setOnArrears(value: string): void {
    this.insurerHardRules.update((list) =>
      list.map((r) =>
        r.ruleType === 'POLICY_STANDING' ? { ...r, onArrears: value as OnArrears } : r,
      ),
    );
  }

  protected policyStandingRule(): InsurerHardRule | undefined {
    return this.insurerHardRules().find((r) => r.ruleType === 'POLICY_STANDING');
  }

  protected policyInForceRule(): InsurerHardRule | undefined {
    return this.insurerHardRules().find((r) => r.ruleType === 'POLICY_IN_FORCE');
  }

  /** Enabled isn't enough: with `STANDBY` the claim is created and arrears evaluated later. */
  protected arrearsBlocks(): boolean {
    const rule = this.policyStandingRule();
    return rule?.enabled === true && rule.onArrears === 'REJECT';
  }

  protected saveHardStop(): void {
    if (this.hardStopSaving()) {
      return;
    }
    this.hardStopSaving.set(true);
    this.hardStopError.set(null);
    this.hardRulesService.saveInsurerWide(this.insurerHardRules()).subscribe({
      next: (rules) => {
        this.insurerHardRules.set(rules);
        this.persistedHardRules.set(structuredClone(rules));
        this.hardStopSaving.set(false);
      },
      error: (e: unknown) => {
        this.hardStopSaving.set(false);
        this.hardStopError.set(this.backendErrorMessage(e));
        this.toastService.show(this.backendErrorMessage(e));
      },
    });
  }

  // ───────────────── Hard exclusions per coverage ─────────────────
  /** Only saved coverages (numeric id) can have exclusions. */
  protected canEditExclusions(c: Coverage): boolean {
    return this.isPersistedId(c.id);
  }

  protected isCauseExcluded(c: Coverage, causeId: number): boolean {
    return (c.excludedClaimCauseIds ?? []).includes(causeId);
  }

  /**
   * Without exclusions the coverage covers its whole branch. Valid, but it decides which sum insured
   * is used to settle, so the UI warns about it.
   */
  protected hasExcludedCauses(c: Coverage): boolean {
    return (c.excludedClaimCauseIds ?? []).length > 0;
  }

  protected coveredCausesLabel(): string {
    const total = this.claimCauses().length;
    return total === 1 ? 'el único hecho generador' : `los ${total} hechos generadores`;
  }

  protected toggleExcludedCause(id: string, causeId: number): void {
    this.draft.update((d) =>
      d
        ? {
            ...d,
            coverages: d.coverages.map((c) => {
              if (c.id !== id) {
                return c;
              }
              const current = c.excludedClaimCauseIds ?? [];
              const has = current.includes(causeId);
              return {
                ...c,
                excludedClaimCauseIds: has
                  ? current.filter((x) => x !== causeId)
                  : [...current, causeId],
              };
            }),
          }
        : d,
    );
  }

  protected removeCoverage(id: string): void {
    this.draft.update((d) => (d ? { ...d, coverages: d.coverages.filter((c) => c.id !== id) } : d));
    if (this.expandedCoverageId() === id) {
      this.expandedCoverageId.set(null);
    }
  }

  protected setCoverageField(id: string, patch: Partial<Coverage>): void {
    this.draft.update((d) =>
      d ? { ...d, coverages: d.coverages.map((c) => (c.id === id ? { ...c, ...patch } : c)) } : d,
    );
  }

  protected setCoverageDeductible(id: string, value: string): void {
    this.setCoverageField(id, { deductibleRatio: this.ratioFromPct(value) });
  }

  protected coverageDeductiblePct(c: Coverage): string {
    return this.pctFromRatio(c.deductibleRatio);
  }

  protected setCoverageReportingWindow(id: string, value: string): void {
    this.setCoverageField(id, { reportingWindowDays: this.intFromStr(value) });
  }

  protected setCoverageMaxClaims(id: string, value: string): void {
    this.setCoverageField(id, { maxAnnualClaims: this.intFromStr(value) });
  }

  protected coverageReportingWindowStr(c: Coverage): string {
    return this.intStr(c.reportingWindowDays);
  }

  protected coverageMaxClaimsStr(c: Coverage): string {
    return this.intStr(c.maxAnnualClaims);
  }

  protected setCoverageWaitingPeriod(id: string, value: string): void {
    this.setCoverageField(id, { waitingPeriodDays: this.intFromStr(value) });
  }

  protected coverageWaitingPeriodStr(c: Coverage): string {
    return this.intStr(c.waitingPeriodDays);
  }

  // ───────────────── Coverages: accordion and rule rows ─────────────────
  /** One coverage open at a time. */
  protected readonly expandedCoverageId = signal<string | null>(null);

  protected isCoverageOpen(c: Coverage): boolean {
    return this.expandedCoverageId() === c.id;
  }

  protected toggleCoverage(c: Coverage): void {
    this.expandedCoverageId.update((open) => (open === c.id ? null : c.id));
  }

  /** Summary shown while collapsed; unset values are omitted rather than shown as "—". */
  protected coverageSummary(c: Coverage): string[] {
    const chips: string[] = [];
    if (c.deductibleRatio != null) {
      chips.push(`Franquicia ${this.pctFromRatio(c.deductibleRatio)}%`);
    }
    if (c.waitingPeriodDays != null) {
      chips.push(`Carencia ${c.waitingPeriodDays} d`);
    }
    if (c.reportingWindowDays != null) {
      chips.push(`Denuncia ${c.reportingWindowDays} d`);
    }
    const police = this.hardRulesOf(c).find((r) => r.ruleType === 'POLICE_DEADLINE');
    if (police?.deadlineHours != null) {
      chips.push(`Policial ${police.deadlineHours} h`);
    }
    if (c.maxAnnualClaims != null) {
      chips.push(`Máx. ${c.maxAnnualClaims}/año`);
    }
    const excluded = (c.excludedClaimCauseIds ?? []).length;
    if (excluded > 0) {
      chips.push(excluded === 1 ? '1 hecho excluido' : `${excluded} hechos excluidos`);
    }
    return chips;
  }

  /** Fixed order, independent of the backend's, so switching coverage doesn't reshuffle rows. */
  private static readonly HARD_RULE_ORDER: HardRuleType[] = [
    'WAITING_PERIOD',
    'REPORT_DEADLINE',
    'POLICE_DEADLINE',
    'MAX_EVENTS_YEAR',
  ];

  protected orderedHardRules(c: Coverage): HardRule[] {
    const rules = this.hardRulesOf(c);
    return ReglasComponent.HARD_RULE_ORDER.map((type) =>
      rules.find((r) => r.ruleType === type),
    ).filter((r): r is HardRule => r != null);
  }

  protected hardRuleHint(type: HardRuleType): string {
    switch (type) {
      case 'WAITING_PERIOD':
        return 'Días desde el alta de la póliza en los que la cobertura todavía no aplica.';
      case 'REPORT_DEADLINE':
        return 'Plazo para denunciar el siniestro a la aseguradora, contado desde el hecho.';
      case 'POLICE_DEADLINE':
        return 'Plazo para hacer la denuncia policial, sobre la fecha que declara el asegurado.';
      case 'MAX_EVENTS_YEAR':
        return 'Cuántos siniestros puede tener el asegurado en esta cobertura en 12 meses.';
    }
  }

  protected hardRuleUnit(type: HardRuleType): string {
    return type === 'POLICE_DEADLINE' ? 'horas' : type === 'MAX_EVENTS_YEAR' ? 'por año' : 'días';
  }

  /**
   * Unifies access: three thresholds are coverage columns (contract terms) and the police deadline
   * lives on the rule itself.
   */
  protected hardRuleValue(c: Coverage, type: HardRuleType): string {
    switch (type) {
      case 'WAITING_PERIOD':
        return this.intStr(c.waitingPeriodDays);
      case 'REPORT_DEADLINE':
        return this.intStr(c.reportingWindowDays);
      case 'POLICE_DEADLINE':
        return this.policeDeadlineStr(c);
      case 'MAX_EVENTS_YEAR':
        return this.intStr(c.maxAnnualClaims);
    }
  }

  protected setHardRuleValue(c: Coverage, type: HardRuleType, value: string): void {
    switch (type) {
      case 'WAITING_PERIOD':
        this.setCoverageWaitingPeriod(c.id, value);
        return;
      case 'REPORT_DEADLINE':
        this.setCoverageReportingWindow(c.id, value);
        return;
      case 'POLICE_DEADLINE':
        this.setPoliceDeadline(c.id, value);
        return;
      case 'MAX_EVENTS_YEAR':
        this.setCoverageMaxClaims(c.id, value);
        return;
    }
  }

  protected toggleCoverageFamilyGroup(c: Coverage): void {
    this.setCoverageField(c.id, { coversFamilyGroup: !c.coversFamilyGroup });
  }

  protected toggleCoverageExhausts(c: Coverage): void {
    this.setCoverageField(c.id, { claimExhaustsCoverage: !c.claimExhaustsCoverage });
  }

  // ───────────────── Coverages: settlement amount ─────────────────
  protected readonly settlementFormulaOptions: SelectOption[] = [
    { value: 'TOTAL_LOSS', label: 'Pérdida total — el bien no está' },
    { value: 'REPAIR', label: 'Reparación — el bien quedó dañado' },
  ];

  protected setCoverageSettlementFormula(id: string, value: string): void {
    this.setCoverageField(id, { settlementFormula: value as SettlementFormula });
  }

  /** Only for total loss: in a repair the ceiling is the quote. */
  protected showsSettlementBasis(c: Coverage): boolean {
    return c.settlementFormula !== 'REPAIR';
  }

  protected readonly settlementBasisOptions: SelectOption[] = [
    { value: 'SUM_INSURED', label: 'La suma asegurada' },
    {
      value: 'LESSER_OF_SUM_AND_REPLACEMENT',
      label: 'El menor entre la suma asegurada y el valor de reposición',
    },
  ];

  protected setCoverageSettlementBasis(id: string, value: string): void {
    this.setCoverageField(id, { settlementBasis: value as SettlementBasis });
  }

  protected coverageSecondEventPct(c: Coverage): string {
    return this.pctFromRatio(c.secondEventRatio);
  }

  protected setCoverageSecondEvent(id: string, value: string): void {
    this.setCoverageField(id, { secondEventRatio: this.ratioFromPct(value) });
  }

  protected toggleDeductPendingInstallments(c: Coverage): void {
    this.setCoverageField(c.id, { deductPendingInstallments: !c.deductPendingInstallments });
  }

  protected toggleDeductOverdueBalance(c: Coverage): void {
    this.setCoverageField(c.id, { deductOverdueBalance: !c.deductOverdueBalance });
  }

  protected setCommonExclusions(items: string[]): void {
    this.patch({ commonExclusions: items });
  }

  // ───────────────── Fast Track thresholds (switch + value) ─────────────────
  /**
   * A threshold is active when it has a value (`null` = not evaluated). Turning it off remembers the
   * last value so turning it back on restores it.
   */
  private readonly ftLastValues = new Map<string, number>();

  /** Initial value when a threshold is first enabled: the seed defaults. */
  private static readonly FT_DEFAULTS: Record<string, number> = {
    minPolicyAgeMonths: 3,
    maxPriorClaims: 0,
    priorClaimsWindowMonths: 12,
    maxClaimedAmountRatio: 50,
  };

  protected ftActive(
    field:
      'minPolicyAgeMonths' | 'maxPriorClaims' | 'priorClaimsWindowMonths' | 'maxClaimedAmountRatio',
  ): boolean {
    return this.draft()?.fastTrack[field] != null;
  }

  protected toggleFtThreshold(
    field:
      'minPolicyAgeMonths' | 'maxPriorClaims' | 'priorClaimsWindowMonths' | 'maxClaimedAmountRatio',
  ): void {
    const ft = this.draft()?.fastTrack;
    if (!ft) {
      return;
    }
    if (ft[field] != null) {
      const current = field === 'maxClaimedAmountRatio' ? (ft[field] ?? 0) * 100 : ft[field];
      if (current != null) {
        this.ftLastValues.set(field, current);
      }
      this.patchFastTrack((c) => ({ ...c, [field]: null }));
      // The window only bounds prior claims: without that threshold it has nothing to bound.
      if (field === 'maxPriorClaims') {
        this.patchFastTrack((c) => ({ ...c, priorClaimsWindowMonths: null }));
      }
      return;
    }
    const restored = this.ftLastValues.get(field) ?? ReglasComponent.FT_DEFAULTS[field];
    if (field === 'maxClaimedAmountRatio') {
      this.setFtMaxRatio(String(restored));
    } else {
      this.patchFastTrack((c) => ({ ...c, [field]: restored }));
    }
  }

  protected setMinPolicyAge(v: string): void {
    this.patchFastTrack((ft) => ({ ...ft, minPolicyAgeMonths: this.intFromStr(v) }));
  }

  protected setMaxPriorClaims(v: string): void {
    this.patchFastTrack((ft) => ({ ...ft, maxPriorClaims: this.intFromStr(v) }));
  }

  protected setPriorClaimsWindow(v: string): void {
    this.patchFastTrack((ft) => ({ ...ft, priorClaimsWindowMonths: this.intFromStr(v) }));
  }

  protected setFtMaxRatio(v: string): void {
    this.patchFastTrack((ft) => ({ ...ft, maxClaimedAmountRatio: this.ratioFromPct(v) }));
  }

  protected toggleFtRequiresUpToDate(): void {
    this.patchFastTrack((ft) => ({ ...ft, requiresUpToDatePolicy: !ft.requiresUpToDatePolicy }));
  }

  protected isFtDoc(code: string): boolean {
    return this.draft()?.fastTrack.requiredDocumentTypes.includes(code) ?? false;
  }

  protected toggleFtDoc(code: string): void {
    this.patchFastTrack((ft) => {
      const has = ft.requiredDocumentTypes.includes(code);
      return {
        ...ft,
        requiredDocumentTypes: has
          ? ft.requiredDocumentTypes.filter((c) => c !== code)
          : [...ft.requiredDocumentTypes, code],
      };
    });
  }

  protected setFtCriteria(items: string[]): void {
    this.patchFastTrack((ft) => ({ ...ft, criteria: items }));
  }

  protected ftMinAgeStr(): string {
    return this.intStr(this.draft()?.fastTrack.minPolicyAgeMonths);
  }
  protected ftMaxPriorStr(): string {
    return this.intStr(this.draft()?.fastTrack.maxPriorClaims);
  }
  protected ftWindowStr(): string {
    return this.intStr(this.draft()?.fastTrack.priorClaimsWindowMonths);
  }
  protected ftMaxRatioPct(): string {
    return this.pctFromRatio(this.draft()?.fastTrack.maxClaimedAmountRatio ?? null);
  }

  // ───────────────── Business rules ─────────────────
  protected setBusinessRules(items: string[]): void {
    this.patch({ businessRules: items });
  }

  // ───────────────── Saving ─────────────────
  /**
   * Saves the Fast Track of the coverage picked in the selector, and only that one: every coverage
   * has its own FAST_TRACK rule, because each demands different documents.
   */
  protected saveFastTrack(): void {
    const d = this.draft();
    if (!d || this.ftSaving()) {
      return;
    }
    this.ftError.set(null);
    const branchId = this.branchIdOf(d);
    if (branchId == null) {
      this.ftError.set('Este ramo todavía no existe en el backend.');
      return;
    }
    const coverageId = this.ftCoverageId();
    if (coverageId == null) {
      this.ftError.set('Elegí una cobertura para guardar su Fast Track.');
      return;
    }
    const ft = d.fastTrack;
    const dto: FastTrackConfigDto = {
      maxClaimedAmountRatio: ft.maxClaimedAmountRatio,
      maxPriorClaims: ft.maxPriorClaims,
      priorClaimsWindowMonths: ft.priorClaimsWindowMonths,
      minPolicyAgeMonths: ft.minPolicyAgeMonths,
      requiresUpToDatePolicy: ft.requiresUpToDatePolicy,
      requiredDocumentTypes: ft.requiredDocumentTypes,
      criteria: ft.criteria,
    };

    this.ftSaving.set(true);
    this.ftService.saveFastTrack(branchId, coverageId, dto).subscribe({
      next: () => {
        this.ftSaving.set(false);
        this.markPersisted('fastTrack');
        // Reload from the backend to show exactly what was persisted.
        this.loadFastTrackForCoverage(d.id, branchId, coverageId, false);
      },
      error: (e: unknown) => {
        this.ftSaving.set(false);
        this.ftError.set(this.backendErrorMessage(e));
      },
    });
  }

  protected saveCommonExclusions(): void {
    const d = this.draft();
    if (!d || this.exclSaving()) {
      return;
    }
    this.exclError.set(null);
    const branchId = this.branchIdOf(d);
    if (branchId == null) {
      this.exclError.set('Este ramo todavía no existe en el backend.');
      return;
    }

    this.exclSaving.set(true);
    this.rulesTextService.saveExclusions(branchId, d.commonExclusions).subscribe({
      next: () => {
        this.exclSaving.set(false);
        this.markPersisted('commonExclusions');
      },
      error: (e: unknown) => {
        this.exclSaving.set(false);
        this.exclError.set(this.backendErrorMessage(e));
      },
    });
  }

  /** Diffs the draft against `loadedCoverages` into creates, updates and deletes. */
  protected saveCoverages(): void {
    const d = this.draft();
    if (!d || this.covSaving()) {
      return;
    }
    this.covError.set(null);
    const branchId = this.branchIdOf(d);
    if (branchId == null) {
      this.covError.set('Este ramo todavía no existe en el backend.');
      return;
    }

    const draftIds = new Set(d.coverages.map((c) => c.id));
    const toDelete = this.loadedCoverages.filter((c) => !draftIds.has(c.id));
    const toCreate = d.coverages.filter((c) => !this.isPersistedId(c.id));
    const toUpdate = d.coverages.filter((c) => this.isPersistedId(c.id));

    const requests: Observable<unknown>[] = [
      ...toDelete.map((c) => this.coveragesService.remove(Number(c.id))),
      ...toCreate.map((c) => this.coveragesService.create(branchId, this.toCoverageRequest(c))),
      ...toUpdate.map((c) => this.coveragesService.update(Number(c.id), this.toCoverageRequest(c))),
      // Exclusions and hard rules only for saved coverages: a new one has no real id until reload.
      ...toUpdate.map((c) =>
        this.exclusionsService.save(branchId, Number(c.id), c.excludedClaimCauseIds ?? []),
      ),
      ...toUpdate
        .filter((c) => this.hardRulesOf(c).length > 0)
        .map((c) => this.hardRulesService.save(branchId, Number(c.id), this.hardRulesOf(c))),
    ];

    this.covSaving.set(true);
    forkJoin(requests.length ? requests : [of(null)]).subscribe({
      next: () => {
        this.covSaving.set(false);
        this.markPersisted('coverages');
        // Reload so newly created coverages get their real ids.
        this.loadCoveragesFromBackend(d);
        this.refreshCoverageSummary();
      },
      error: (e: unknown) => {
        this.covSaving.set(false);
        this.covError.set(this.backendErrorMessage(e));
      },
    });
  }

  private toCoverageRequest(c: Coverage): CoverageUpsertRequest {
    return {
      name: c.name,
      clause: c.clause || null,
      deductibleRatio: c.deductibleRatio,
      reportingWindowDays: c.reportingWindowDays,
      maxAnnualClaims: c.maxAnnualClaims,
      waitingPeriodDays: c.waitingPeriodDays,
      coversFamilyGroup: c.coversFamilyGroup,
      claimExhaustsCoverage: c.claimExhaustsCoverage,
      settlementFormula: c.settlementFormula,
      settlementBasis: c.settlementBasis,
      secondEventRatio: c.secondEventRatio,
      deductPendingInstallments: c.deductPendingInstallments,
      deductOverdueBalance: c.deductOverdueBalance,
      exclusions: c.exclusions,
    };
  }

  /** Numeric ids exist in the backend; `cov-` ids are local, unsaved coverages. */
  private isPersistedId(id: string): boolean {
    return /^\d+$/.test(id);
  }

  protected saveDocuments(): void {
    const d = this.draft();
    if (!d || this.docSaving()) {
      return;
    }
    this.docError.set(null);
    const branchId = this.branchIdOf(d);
    if (branchId == null) {
      this.docError.set('Este ramo todavía no existe en el backend.');
      return;
    }
    const changed = this.changedClaimCauses();
    if (changed.length === 0) {
      return;
    }

    this.docSaving.set(true);
    forkJoin(
      changed.map((claimCauseId) =>
        this.documentsService.save(
          branchId,
          claimCauseId,
          d.requiredDocumentsByClaimCause[claimCauseId] ?? [],
        ),
      ),
    ).subscribe({
      next: () => {
        this.docSaving.set(false);
        this.markPersisted('requiredDocumentsByClaimCause');
        changed.forEach((claimCauseId) => this.loadDocumentsFromBackend(d, claimCauseId));
      },
      error: (e: unknown) => {
        this.docSaving.set(false);
        this.docError.set(this.backendErrorMessage(e));
      },
    });
  }

  // ───────────────── Document agenda matrix ─────────────────
  protected isDocRequiredFor(claimCauseId: number, code: string): boolean {
    return this.draft()?.requiredDocumentsByClaimCause[claimCauseId]?.includes(code) ?? false;
  }

  protected toggleDocFor(claimCauseId: number, code: string): void {
    this.draft.update((d) => {
      if (!d) {
        return d;
      }
      const current = d.requiredDocumentsByClaimCause[claimCauseId] ?? [];
      const next = current.includes(code) ? current.filter((c) => c !== code) : [...current, code];
      return {
        ...d,
        requiredDocumentsByClaimCause: { ...d.requiredDocumentsByClaimCause, [claimCauseId]: next },
      };
    });
  }

  protected requiredCountLabel(claimCauseId: number): string {
    const count = this.draft()?.requiredDocumentsByClaimCause[claimCauseId]?.length ?? 0;
    if (count === 0) {
      return 'sin obligatorios';
    }
    return count === 1 ? '1 obligatorio' : `${count} obligatorios`;
  }

  /** Only changed agendas are saved: resending all would overwrite others' concurrent edits. */
  private changedClaimCauses(): number[] {
    const current = this.draft()?.requiredDocumentsByClaimCause ?? {};
    const base = this.persisted()?.requiredDocumentsByClaimCause ?? {};
    return this.claimCauses()
      .map((c) => c.id)
      .filter((id) => JSON.stringify(current[id] ?? []) !== JSON.stringify(base[id] ?? []));
  }

  protected saveBusinessRules(): void {
    const d = this.draft();
    if (!d || this.rulesSaving()) {
      return;
    }
    this.rulesError.set(null);
    const branchId = this.branchIdOf(d);
    if (branchId == null) {
      this.rulesError.set('Este ramo todavía no existe en el backend.');
      return;
    }
    this.rulesSaving.set(true);
    this.rulesTextService.saveBusinessRules(branchId, d.businessRules).subscribe({
      next: () => {
        this.rulesSaving.set(false);
        this.markPersisted('businessRules');
        this.loadBusinessRulesFromBackend(d);
      },
      error: (e: unknown) => {
        this.rulesSaving.set(false);
        this.rulesError.set(this.backendErrorMessage(e));
      },
    });
  }

  private backendErrorMessage(e: unknown): string {
    if (e instanceof HttpErrorResponse) {
      if (e.status === 403) {
        return 'No tenés permiso para editar reglas (se requiere rol Referente).';
      }
      if (e.status === 0) {
        return 'No se pudo contactar al backend de reglas (¿el servicio está arriba?).';
      }
      const detail = (e.error as { detail?: string } | null)?.detail;
      return detail ?? `El backend rechazó el guardado (${e.status}).`;
    }
    return e instanceof Error ? e.message : 'No se pudo guardar.';
  }

  // ───────────────── Helpers ─────────────────
  private patch(partial: Partial<RamoRules>): void {
    this.draft.update((d) => (d ? { ...d, ...partial } : d));
  }

  private patchFastTrack(fn: (ft: FastTrackConfig) => FastTrackConfig): void {
    this.draft.update((d) => (d ? { ...d, fastTrack: fn(d.fastTrack) } : d));
  }

  private pctFromRatio(ratio: number | null): string {
    return ratio == null ? '' : String(Math.round(ratio * 1000) / 10);
  }

  private ratioFromPct(value: string): number | null {
    const t = value.trim();
    if (t === '') {
      return null;
    }
    const n = Number(t);
    return Number.isFinite(n) ? n / 100 : null;
  }

  private intFromStr(value: string): number | null {
    const t = value.trim();
    return t === '' || !Number.isFinite(Number(t)) ? null : Math.max(0, Math.trunc(Number(t)));
  }

  private intStr(n: number | null | undefined): string {
    return n == null ? '' : String(n);
  }
}
