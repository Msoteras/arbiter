import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ParamMap } from '@angular/router';
import { Observable, finalize, forkJoin, of, skip, switchMap, tap } from 'rxjs';

import {
  Coverage,
  DeductibleBasis,
  DocumentTypeDef,
  FastTrackConfig,
  BranchRules,
  SettlementBasis,
  SettlementFormula,
} from '../../../core/models/business-rules';
import { BranchOption, BranchesService } from '../branches.service';
import { FastTrackConfigDto, FastTrackRulesService } from '../fast-track-rules.service';
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
import { DocumentTypeService } from '../../../core/services/document-type.service';
import { BusinessRulesTextService } from '../business-rules-text.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { IconButtonComponent } from '../../../shared/ui/icon-button/icon-button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { FieldComponent } from '../../../shared/ui/field/field.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { TextareaComponent } from '../../../shared/ui/textarea/textarea.component';
import { ModalComponent } from '../../../shared/ui/modal/modal.component';
import { SettlementAuthoritiesConfigComponent } from '../settlement-authorities-config/settlement-authorities-config.component';
import { ScoringConfigComponent } from '../scoring-config/scoring-config.component';
import { FraudConfigComponent } from '../fraud-config/fraud-config.component';
import { ProvidersConfigComponent } from '../providers-config/providers-config.component';
import { DerivationsConfigComponent } from '../derivations-config/derivations-config.component';
import { ResolutionTargetConfigComponent } from '../resolution-target-config/resolution-target-config.component';
import { BrandComponent } from '../brand/brand.component';
import { RuleHistoryComponent } from '../rule-history/rule-history.component';
import { SaveBarComponent } from '../../../shared/ui/save-bar/save-bar.component';
import { MonthsInputComponent } from './months-input.component';
import { StringListEditorComponent } from './string-list-editor.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { SkeletonComponent } from '../../../shared/ui/skeleton/skeleton.component';
import { InfoTipComponent } from '../../../shared/ui/info-tip/info-tip.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { SwitchComponent } from '../../../shared/ui/switch/switch.component';
import { TableComponent } from '../../../shared/ui/table/table.component';
import { TabOption, TabsComponent } from '../../../shared/ui/tabs/tabs.component';
import { CheckboxComponent } from '../../../shared/ui/checkbox/checkbox.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import {
  MenuButtonComponent,
  MenuItem,
} from '../../../shared/ui/menu-button/menu-button.component';
import { RuleBlockComponent } from '../../../shared/ui/rule-block/rule-block.component';
import { RuleGroupComponent } from '../../../shared/ui/rule-group/rule-group.component';
import { FraudRuleService } from '../fraud-rule.service';
import { RiskBand } from '../../../core/models/risk-band';
import { ToastService } from '../../../shared/ui/toast/toast.service';
import {
  ONBOARDING_STEP_LINE,
  OnboardingStepId,
  onboardingStepArea,
} from '../../../core/onboarding/onboarding';
import { OnboardingService } from '../../../core/onboarding/onboarding.service';
import { accordion, fadeInUp, staggerReveal } from '../../../shared/animations';
import { OnboardingTargetDirective } from '../../../core/onboarding/onboarding-target.directive';
import { AuthSessionService } from '../../../core/auth/auth-session.service';
import {
  RulesTreeBranch,
  RulesTreeComponent,
  RulesTreeCoverage,
  RulesTreeSection,
  RulesTreeSelection,
  RulesTreeStatus,
} from './rules-tree.component';

type BranchTabId = 'summary' | 'exclusions' | 'documents' | 'derivations' | 'rules' | 'authorities';
type CoverageTabId = 'data' | 'causes' | 'terms' | 'settlement' | 'fastTrack';

/**
 * The draft a tab edits. Moving to a tab of another slice with unsaved changes asks first, the
 * same as leaving the branch. Derivations and authorities keep their own drafts: no slice here.
 */
type Slice = 'coverages' | 'commonExclusions' | 'documents' | 'fastTrack' | 'businessRules' | null;

/** The backend stores whole months; a typed fraction (1,5 años is fine, 1,5 meses isn't) is rounded. */
function wholeMonths(months: number | null): number | null {
  return months == null ? null : Math.max(0, Math.round(months));
}

/** Right-panel views that don't depend on the selected branch. */
type GeneralView = 'hardStop' | 'scoring' | 'fraud' | 'providers' | 'target' | 'brand' | 'history';

/** A Fast Track condition the referent adds or removes as a block. */
type FtCondition = 'age' | 'prior' | 'amount' | 'upToDate' | 'docs';

/** "Agregar condición" offers them in this order, the same the blocks are shown in. */
const FT_CONDITIONS: { id: FtCondition; label: string }[] = [
  { id: 'age', label: 'Antigüedad de la póliza' },
  { id: 'prior', label: 'Siniestros previos' },
  { id: 'amount', label: 'Monto reclamado' },
  { id: 'upToDate', label: 'Póliza al día' },
  { id: 'docs', label: 'Documentos adjuntos' },
];

/** A tree item and the content beside it: three configuration levels, plus the audit history. */
type Level = 'insurer' | 'branch' | 'coverage' | 'audit';

/**
 * Three configuration levels: the insurer (its own sections), each branch and each coverage of a
 * branch, picked from a tree. A branch is loaded whole; its coverages are views over that same
 * draft, with per-slice drafts each saved by its own button. The UI shows percentages (0..100);
 * the backend uses fractions (0..1).
 */
@Component({
  selector: 'app-rules',
  imports: [
    OnboardingTargetDirective,
    ButtonComponent,
    IconButtonComponent,
    CardComponent,
    EmptyStateComponent,
    FieldComponent,
    InputComponent,
    TextareaComponent,
    ModalComponent,
    ScoringConfigComponent,
    FraudConfigComponent,
    ProvidersConfigComponent,
    DerivationsConfigComponent,
    SettlementAuthoritiesConfigComponent,
    ResolutionTargetConfigComponent,
    BrandComponent,
    RuleHistoryComponent,
    RulesTreeComponent,
    SaveBarComponent,
    StringListEditorComponent,
    MonthsInputComponent,
    InlineLoadingComponent,
    SkeletonComponent,
    NgTemplateOutlet,
    InfoTipComponent,
    BadgeComponent,
    SwitchComponent,
    TableComponent,
    TabsComponent,
    CheckboxComponent,
    SelectComponent,
    MenuButtonComponent,
    RuleBlockComponent,
    RuleGroupComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [staggerReveal, fadeInUp, accordion],
  templateUrl: './rules.component.html',
  // Order matters: the files are concatenated as listed; swapping them changes the cascade.
  styleUrls: ['./rules.component.scss', './rules-sections.scss', './rules-levels.scss'],
})
export class RulesComponent {
  private readonly branchesService = inject(BranchesService);
  private readonly ftService = inject(FastTrackRulesService);
  private readonly coveragesService = inject(CoveragesRulesService);
  private readonly exclusionsService = inject(CoverageExclusionsService);
  private readonly hardRulesService = inject(HardRulesService);
  private readonly fraudRuleService = inject(FraudRuleService);
  private readonly documentsService = inject(DocumentRulesService);
  private readonly documentTypesService = inject(DocumentTypeService);
  private readonly rulesTextService = inject(BusinessRulesTextService);
  private readonly toastService = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly onboarding = inject(OnboardingService);
  private readonly session = inject(AuthSessionService);
  protected readonly readOnly = computed(
    () => this.session.session()?.rol !== 'REFERENTE_ASEGURADORA',
  );
  /** A request older than this screen was already handled by the address it opened with. */
  private reopenedFocusId = this.onboarding.focusRequest()?.id ?? null;

  protected readonly ftSaving = signal(false);
  protected readonly ftError = signal<string | null>(null);
  // Fast Track is configured per coverage (insurer_rule.coverage_id): `draft.fastTrack` holds the
  // config of the selected coverage, `ftConfigs` every coverage of the branch as stored.
  protected readonly ftCoverageId = signal<number | null>(null);
  private readonly ftConfigs = signal<Record<string, FastTrackConfigDto | 'failed'>>({});
  /** The selected coverage's stored config hasn't arrived yet. */
  protected readonly ftLoading = computed(() => {
    const id = this.ftCoverageId();
    return id != null && this.ftConfigs()[String(id)] === undefined;
  });
  protected readonly ftLoadFailed = computed(() => {
    const id = this.ftCoverageId();
    return id != null && this.ftConfigs()[String(id)] === 'failed';
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

  // Documents the selected branch may ask for: the rows of the Documents matrix and the Fast Track list.
  protected readonly docTypes = signal<readonly DocumentTypeDef[]>([]);
  // Claim causes of the selected branch: used by the coverage exclusions and the Documents matrix.
  protected readonly claimCauses = signal<ClaimCauseOption[]>([]);

  // Renaming is the only branch edit exposed here; creating/deleting branches isn't in the UI.
  protected readonly renaming = signal(false);
  protected readonly renameError = signal<string | null>(null);
  /** The name only becomes an input after the pencil is pressed. */
  protected readonly editingName = signal(false);
  /** Renames ask for confirmation: the name changes everywhere the insurer sees the branch. */
  protected readonly showRenameConfirm = signal(false);

  /** Section tooltips: the same line the setup checklist shows for each step. */
  protected readonly stepLine = ONBOARDING_STEP_LINE;

  protected readonly branchTabs: (TabOption & { id: BranchTabId })[] = [
    { id: 'summary', label: 'Resumen' },
    { id: 'exclusions', label: 'Exclusiones comunes' },
    { id: 'documents', label: 'Agenda documental' },
    { id: 'derivations', label: 'Derivaciones' },
    { id: 'rules', label: 'Reglas de negocio' },
    { id: 'authorities', label: 'Atribuciones' },
  ];
  protected readonly coverageTabs: (TabOption & { id: CoverageTabId })[] = [
    { id: 'data', label: 'Datos' },
    { id: 'causes', label: 'Hechos generadores' },
    { id: 'terms', label: 'Plazos y topes' },
    { id: 'settlement', label: 'Liquidación' },
    { id: 'fastTrack', label: 'Fast Track' },
  ];
  protected readonly branchTab = signal<BranchTabId>('summary');
  /** Kept when another coverage is picked, so going through coverages compares the same tab. */
  protected readonly coverageTab = signal<CoverageTabId>('data');
  /** Null at branch level. A coverage is a view over the open branch's draft, not a load of its own. */
  protected readonly selectedCoverageId = signal<string | null>(null);
  private readonly tabStrip = viewChild(TabsComponent);

  protected readonly branches = signal<BranchRules[]>([]);
  protected readonly branchesLoading = signal(true);
  // Keeps the detail panel from filling in piecemeal while its requests are in flight.
  protected readonly detailLoading = signal(false);
  protected readonly detailFailed = signal(false);
  /** Branch whose detail is loading, so responses for a branch left behind are ignored. */
  private detailToken: string | null = null;
  private pendingDetail = 0;
  /** Runs once the open branch finished loading, e.g. opening a coverage a link points at. */
  private afterDetailLoad: (() => void) | null = null;
  protected readonly selectedId = signal<string | null>(null);
  protected readonly view = signal<'branch' | GeneralView>('branch');
  protected readonly draft = signal<BranchRules | null>(null);

  /**
   * Last state confirmed by the backend; unsaved changes are measured against it. Synced per slice,
   * not whole: loads land at different times and a full sync would wipe other tabs' pending edits.
   */
  private readonly persisted = signal<BranchRules | null>(null);

  private markPersisted(...fields: (keyof BranchRules)[]): void {
    const current = this.draft();
    if (!current) {
      return;
    }
    this.persisted.update((base) => {
      const next: BranchRules = base ? { ...base } : structuredClone(current);
      for (const field of fields) {
        Object.assign(next, { [field]: structuredClone(current[field]) });
      }
      return next;
    });
  }

  private sliceChanged(...fields: (keyof BranchRules)[]): boolean {
    const current = this.draft();
    const base = this.persisted();
    if (!current || !base) {
      return false;
    }
    return fields.some((field) => JSON.stringify(current[field]) !== JSON.stringify(base[field]));
  }

  private restoreSlice(...fields: (keyof BranchRules)[]): void {
    const base = this.persisted();
    if (!base) {
      return;
    }
    this.draft.update((current) => {
      if (!current) {
        return current;
      }
      const next: BranchRules = { ...current };
      for (const field of fields) {
        Object.assign(next, { [field]: structuredClone(base[field]) });
      }
      return next;
    });
  }

  protected readonly covDirty = computed(() => this.sliceChanged('coverages'));
  protected readonly exclDirty = computed(() => this.sliceChanged('commonExclusions'));
  protected readonly ftDirty = computed(() => this.sliceChanged('fastTrack'));
  protected readonly docDirty = computed(() =>
    this.sliceChanged('requiredDocumentsByClaimCause', 'documentBandsByClaimCause'),
  );
  protected readonly rulesDirty = computed(() => this.sliceChanged('businessRules'));

  protected discardCoverages(): void {
    this.restoreSlice('coverages');
    this.covError.set(null);
    // A coverage added and never saved is gone after this: its branch takes its place.
    const id = this.selectedCoverageId();
    if (id != null && !this.draft()?.coverages.some((c) => c.id === id)) {
      this.selectedCoverageId.set(null);
      this.ftCoverageId.set(null);
      this.showBranchTab('summary');
    }
  }

  protected discardCommonExclusions(): void {
    this.restoreSlice('commonExclusions');
    this.exclError.set(null);
  }

  protected discardFastTrack(): void {
    this.restoreSlice('fastTrack');
    this.ftPending.set(new Set());
    this.ftError.set(null);
  }

  protected discardDocuments(): void {
    this.restoreSlice('requiredDocumentsByClaimCause', 'documentBandsByClaimCause');
    this.docError.set(null);
  }

  protected discardBusinessRules(): void {
    this.restoreSlice('businessRules');
    this.rulesError.set(null);
  }

  // Cached because the counts and the branch list are independent requests with no guaranteed
  // order; both callbacks reapply it, so whichever arrives last wins.
  private coverageCounts = new Map<string, number>();
  /** Until the summary answers, a branch's count is unknown, not zero. */
  private readonly coverageSummaryLoaded = signal(false);

  constructor() {
    // Only the first list picks a branch: a refresh, e.g. one another referent created, updates the
    // list and leaves the referent where they are. Selection goes by id, so it survives the swap.
    let firstList = true;
    this.branchesService.list().subscribe({
      next: (branches) => {
        const shells = branches.map((b) => this.shellFromBranch(b));
        this.branches.set(shells);
        this.branchesLoading.set(false);
        this.applyCoverageCounts();
        if (!firstList) {
          return;
        }
        firstList = false;
        if (!this.openLinkedView(this.route.snapshot.queryParamMap) && shells.length > 0) {
          this.select(shells[0]);
        }
      },
      error: () => this.branchesLoading.set(false),
    });
    this.refreshCoverageSummary();
    this.loadInsurerHardRules();
    // Only to say, on the Fast Track tab, whether a fraud record also takes it away.
    this.fraudRuleService
      .get()
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: (rule) => this.fraudBlocksFastTrack.set(rule.blocksFastTrack),
        error: () => this.fraudBlocksFastTrack.set(false),
      });

    // A link arriving while the screen is already open, e.g. "Siguiente" on the setup toast.
    this.route.queryParamMap.pipe(skip(1), takeUntilDestroyed()).subscribe((params) => {
      if (!this.branchesLoading()) {
        this.leaveCurrentView(() => this.openLinkedView(params));
      }
    });

    // The same link again, e.g. after moving to another tab by hand: the address does not change,
    // so nothing above fires and the step is reopened from the request itself.
    effect(() => {
      const request = this.onboarding.focusRequest();
      if (
        !request?.sameUrl ||
        request.id === this.reopenedFocusId ||
        onboardingStepArea(request.step) !== 'rules' ||
        this.branchesLoading()
      ) {
        return;
      }
      this.reopenedFocusId = request.id;
      untracked(() =>
        this.leaveCurrentView(() => this.openLinkedView(this.route.snapshot.queryParamMap)),
      );
    });
  }

  /**
   * Opens what a setup checklist link points at: `?seccion=` for an insurer section, or `?ramo=`,
   * `?cobertura=` and `?solapa=` for a branch or coverage tab. Without `ramo`, the first branch.
   * False when the link names nothing, so the caller falls back to its default.
   */
  private openLinkedView(params: ParamMap): boolean {
    const section = params.get('seccion');
    if (section && this.visibleGeneralSections().some((g) => g.id === section)) {
      this.selectGeneral(section as GeneralView);
      return true;
    }
    const branchId = params.get('ramo');
    const coverageId = params.get('cobertura');
    const tab = params.get('solapa');
    if (!branchId && !coverageId && !tab) {
      return false;
    }
    const branch = this.branches().find((b) => b.id === branchId) ?? this.branches()[0];
    if (!branch) {
      return false;
    }
    const coverageTab = this.coverageTabs.find((t) => t.id === tab)?.id;
    if (coverageId || coverageTab) {
      this.openLinkedCoverage(branch, coverageId, coverageTab ?? 'data');
      return true;
    }
    this.openBranch(branch, this.branchTabs.find((t) => t.id === tab)?.id ?? 'summary');
    return true;
  }

  /**
   * A coverage tab named without a coverage, e.g. Fast Track from the checklist, opens the first
   * coverage still missing it. A branch without coverages opens on its summary.
   */
  private openLinkedCoverage(
    branch: BranchRules,
    coverageId: string | null,
    tab: CoverageTabId,
  ): void {
    if (!this.isBranchOpen(branch.id)) {
      this.select(branch);
    }
    this.whenDetailLoaded(() => {
      const coverages = this.draft()?.coverages ?? [];
      const target =
        coverages.find((c) => c.id === coverageId) ??
        (tab === 'fastTrack'
          ? coverages.find((c) => this.coverageMissingFastTrack(c))
          : undefined) ??
        coverages[0];
      if (target) {
        this.openCoverage(branch, target.id, tab);
      } else {
        this.openBranch(branch, 'summary');
      }
    });
  }

  /** Also called after saving coverages, since adding or removing one changes the count. */
  private refreshCoverageSummary(): void {
    this.coveragesService.summary().subscribe({
      next: (counts) => {
        this.coverageCounts = new Map(counts.map((c) => [String(c.branchId), c.coverageCount]));
        this.coverageSummaryLoaded.set(true);
        this.applyCoverageCounts();
      },
      error: () => {
        /* best-effort: keep the last known counts */
      },
    });
  }

  /** A branch missing from the summary has no coverages: the summary only lists the others. */
  private applyCoverageCounts(): void {
    if (!this.coverageSummaryLoaded()) {
      return;
    }
    this.branches.update((list) =>
      list.map((r) => ({ ...r, coverageCount: this.coverageCounts.get(r.id) ?? 0 })),
    );
  }

  /** Claim causes at least one coverage of the branch covers. */
  private readonly coveredClaimCauses = computed(() => {
    const coverages = this.draft()?.coverages ?? [];
    return this.claimCauses().filter((cause) =>
      coverages.some((c) => !(c.excludedClaimCauseIds ?? []).includes(cause.id)),
    );
  });

  /**
   * The only setting a branch can't work without: no coverage, no policy matches and no claim
   * can be filed. Everything else is optional and only flagged when left empty.
   */
  private branchTabStatus(tab: BranchTabId): RulesTreeStatus {
    const d = this.draft();
    // Mid-load the branch looks empty: flagging it would show a marker that then disappears.
    if (!d || this.detailLoading() || this.detailFailed()) {
      return null;
    }
    switch (tab) {
      case 'summary':
        return d.coverages.length === 0 ? 'required' : null;
      case 'documents':
        // An agenda that never arrived is unknown, not empty.
        return this.coveredClaimCauses().some(
          (cause) => d.requiredDocumentsByClaimCause[cause.id]?.length === 0,
        )
          ? 'pending'
          : null;
      default:
        return null;
    }
  }

  /** A row of the summary's map that no coverage of the branch covers. */
  protected causeHasCoverage(causeId: number): boolean {
    return this.coveredClaimCauses().some((cause) => cause.id === causeId);
  }

  /** Branch tabs whose component renders itself, loading included. */
  protected readonly ownPanelTab = computed(
    () =>
      this.level() === 'branch' &&
      (this.branchTab() === 'derivations' || this.branchTab() === 'authorities'),
  );

  protected readonly branchTabOptions = computed(() =>
    this.branchTabs.map((tab) => ({ ...tab, status: this.branchTabStatus(tab.id) })),
  );

  protected readonly coverageTabOptions = computed(() => {
    const c = this.selectedCoverage();
    const missingFt = c != null && this.coverageMissingFastTrack(c);
    return this.coverageTabs.map((tab) => ({
      ...tab,
      status: tab.id === 'fastTrack' && missingFt ? ('required' as const) : null,
    }));
  });

  /** Known only once its stored config arrived; the backend answers an empty one when there is none. */
  protected coverageMissingFastTrack(c: Coverage): boolean {
    const stored = this.ftConfigs()[c.id];
    return stored != null && stored !== 'failed' && stored.requiresUpToDatePolicy == null;
  }

  private beginDetailLoad(rulesId: string): void {
    this.detailToken = rulesId;
    // Held until settleDetailLoad: a request answering at once would otherwise end the load
    // before the next one starts.
    this.pendingDetail = 1;
    this.afterDetailLoad = null;
    this.detailFailed.set(false);
    this.detailLoading.set(true);
  }

  private finishDetailLoad(): void {
    this.detailLoading.set(false);
    const next = this.afterDetailLoad;
    this.afterDetailLoad = null;
    if (next && !this.detailFailed()) {
      next();
    }
  }

  /** Right away when the open branch is loaded; otherwise once it is. */
  private whenDetailLoaded(next: () => void): void {
    if (this.detailLoading()) {
      this.afterDetailLoad = next;
    } else if (!this.detailFailed()) {
      next();
    }
  }

  protected retryDetailLoad(): void {
    const branch = this.branches().find((b) => b.id === this.selectedId());
    if (!branch) {
      return;
    }
    const coverageId = this.selectedCoverageId();
    this.select(branch);
    if (coverageId != null) {
      this.openLinkedCoverage(branch, coverageId, this.coverageTab());
    }
  }

  /**
   * The loader turns off only when every tracked request finished, including the ones chained off
   * the coverages response. Requests for a branch the user already left don't count.
   */
  private trackDetail<T>(rulesId: string, source: Observable<T>): Observable<T> {
    if (this.detailToken !== rulesId) {
      return source;
    }
    this.pendingDetail += 1;
    return source.pipe(
      tap({
        error: () => {
          if (this.detailToken === rulesId) {
            this.detailFailed.set(true);
          }
        },
      }),
      finalize(() => {
        if (this.detailToken !== rulesId) {
          return;
        }
        this.pendingDetail -= 1;
        if (this.pendingDetail === 0) {
          this.finishDetailLoad();
        }
      }),
    );
  }

  /** Releases the hold of beginDetailLoad: the load ends here when every request already answered. */
  private settleDetailLoad(): void {
    this.pendingDetail -= 1;
    if (this.pendingDetail === 0) {
      this.finishDetailLoad();
    }
  }

  /** Only id + name; the rest is loaded from the backend on select. */
  private shellFromBranch(branch: BranchOption): BranchRules {
    return {
      id: String(branch.id),
      name: branch.name,
      coverages: [],
      coverageCount: 0,
      commonExclusions: [],
      requiredDocumentsByClaimCause: {},
      documentBandsByClaimCause: {},
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

  private readonly generalSections: { id: GeneralView; label: string; referentOnly?: boolean }[] = [
    { id: 'hardStop', label: 'Hard Stop' },
    { id: 'scoring', label: 'Puntaje de riesgo' },
    { id: 'fraud', label: 'Gestión de fraude' },
    // Its own entry: a repair shop has nothing to do with fraud.
    { id: 'providers', label: 'Proveedores externos' },
    { id: 'target', label: 'Objetivo de resolución' },
    { id: 'brand', label: 'Marca', referentOnly: true },
    // 'history' is deliberately not here: it configures nothing and has its own Audit block.
  ];

  protected readonly visibleGeneralSections = computed(() =>
    this.generalSections.filter((s) => !s.referentOnly || !this.readOnly()),
  );

  /** The setup checklist step each insurer section completes, for its dot in the tree. */
  private static readonly SECTION_STEPS: Partial<Record<GeneralView, OnboardingStepId>> = {
    hardStop: 'HARD_STOP',
    scoring: 'SCORING',
    fraud: 'FRAUD',
    providers: 'PROVIDERS',
    target: 'TARGET',
  };

  /** Only the referent has a checklist; an optional step left pending isn't flagged. */
  private sectionStatus(id: GeneralView): RulesTreeStatus {
    const step = RulesComponent.SECTION_STEPS[id];
    const pending = (this.onboarding.steps() ?? []).some(
      (s) => s.step === step && s.status === 'PENDING' && s.level !== 'OPTIONAL',
    );
    return step != null && pending ? 'required' : null;
  }

  protected selectGeneral(section: GeneralView): void {
    this.view.set(section);
  }

  protected readonly level = computed<Level>(() => {
    const view = this.view();
    if (view === 'history') {
      return 'audit';
    }
    if (view !== 'branch') {
      return 'insurer';
    }
    return this.selectedCoverageId() == null ? 'branch' : 'coverage';
  });

  protected readonly selectedCoverage = computed(() => {
    const id = this.selectedCoverageId();
    return id == null ? undefined : this.draft()?.coverages.find((c) => c.id === id);
  });

  // ───────── Tree ─────────

  /** Open branches in the tree. The open branch is added when picked, never removed by itself. */
  private readonly expandedBranches = signal<ReadonlySet<string>>(new Set());
  /** Coverages of branches other than the open one, read when the tree expands them. */
  private readonly treeCoverages = signal<Record<string, RulesTreeCoverage[]>>({});

  protected readonly treeSections = computed<RulesTreeSection[]>(() =>
    this.visibleGeneralSections().map((s) => ({
      id: s.id,
      label: s.label,
      status: this.sectionStatus(s.id),
    })),
  );

  protected readonly treeBranches = computed<RulesTreeBranch[]>(() => {
    const d = this.draft();
    const expanded = this.expandedBranches();
    return this.branches().map((b) => {
      const open = d?.id === b.id;
      return {
        id: b.id,
        name: b.name,
        count: this.coverageSummaryLoaded() ? b.coverageCount : null,
        status: this.branchStatus(b),
        expanded: expanded.has(b.id),
        coverages: open
          ? this.detailLoading()
            ? null
            : d.coverages.map((c) => ({
                id: c.id,
                name: c.name,
                status: this.coverageMissingFastTrack(c) ? ('required' as const) : null,
              }))
          : (this.treeCoverages()[b.id] ?? null),
      };
    });
  });

  /** No coverage is the one thing missing; anything else is only known for the open branch. */
  private branchStatus(b: BranchRules): RulesTreeStatus {
    if (this.coverageSummaryLoaded() && b.coverageCount === 0) {
      return 'required';
    }
    const d = this.draft();
    if (d?.id !== b.id || this.detailLoading() || this.detailFailed()) {
      return null;
    }
    if (d.coverages.some((c) => this.coverageMissingFastTrack(c))) {
      return 'required';
    }
    return this.branchTabStatus('documents');
  }

  protected readonly treeSelection = computed<RulesTreeSelection>(() => {
    const view = this.view();
    if (view === 'history') {
      return { kind: 'none' };
    }
    if (view !== 'branch') {
      return { kind: 'section', id: view };
    }
    const branchId = this.selectedId();
    const coverageId = this.selectedCoverageId();
    if (branchId == null) {
      return { kind: 'none' };
    }
    return coverageId == null
      ? { kind: 'branch', branchId }
      : { kind: 'coverage', branchId, coverageId };
  });

  protected toggleBranch(id: string): void {
    const open = new Set(this.expandedBranches());
    if (open.has(id)) {
      open.delete(id);
    } else {
      open.add(id);
      this.loadTreeCoverages(id);
    }
    this.expandedBranches.set(open);
  }

  private expandBranch(id: string): void {
    if (!this.expandedBranches().has(id)) {
      this.expandedBranches.set(new Set([...this.expandedBranches(), id]));
    }
  }

  /** Names only, for the tree: the branch's detail loads when it is picked. */
  private loadTreeCoverages(id: string): void {
    const branchId = Number(id);
    if (this.draft()?.id === id || this.treeCoverages()[id] || !Number.isInteger(branchId)) {
      return;
    }
    this.ftService.listCoverages(branchId).subscribe({
      next: (list) =>
        this.treeCoverages.update((all) => ({
          ...all,
          [id]: list.map((c) => ({ id: String(c.id), name: c.name, status: null })),
        })),
      error: () => this.treeCoverages.update((all) => ({ ...all, [id]: [] })),
    });
  }

  /** The branch being left keeps its coverages in the tree, as last stored. */
  private keepTreeCoverages(): void {
    const left = this.persisted();
    if (left) {
      this.treeCoverages.update((all) => ({
        ...all,
        [left.id]: left.coverages.map((c) => ({ id: c.id, name: c.name, status: null })),
      }));
    }
  }

  // ───────── Header ─────────

  protected readonly heading = computed<{
    crumbs: { label: string; branchId?: string }[];
    title: string;
    level: string | null;
  }>(() => {
    switch (this.level()) {
      case 'audit':
        return { crumbs: [{ label: 'Auditoría' }], title: 'Historial de cambios', level: null };
      case 'insurer': {
        const section = this.generalSections.find((s) => s.id === this.view());
        return {
          crumbs: [{ label: 'Aseguradora' }],
          title: section?.label ?? '',
          level: 'Aseguradora',
        };
      }
      case 'branch':
        return { crumbs: [{ label: 'Ramos' }], title: this.renameFrom(), level: 'Ramo' };
      case 'coverage': {
        const branchId = this.selectedId() ?? '';
        const id = this.selectedCoverageId();
        const name =
          this.selectedCoverage()?.name ??
          this.treeCoverages()[branchId]?.find((c) => c.id === id)?.name ??
          '';
        return {
          crumbs: [{ label: 'Ramos' }, { label: this.renameFrom(), branchId }],
          title: name || 'Cobertura sin nombre',
          level: 'Cobertura',
        };
      }
    }
  });

  // ───────── Navigation ─────────

  /** Unsaved edits in the open view; leaving it would drop them without a word. */
  private readonly hasUnsavedChanges = computed(() => {
    switch (this.view()) {
      case 'branch':
        return (
          this.covDirty() ||
          this.exclDirty() ||
          this.ftDirty() ||
          this.docDirty() ||
          this.rulesDirty()
        );
      case 'hardStop':
        return this.hardStopDirty();
      default:
        return false;
    }
  });

  /** Where the user wanted to go when the discard confirmation opened. */
  protected readonly pendingNavigation = signal<(() => void) | null>(null);

  protected goToSection(id: string, menu: HTMLElement, editor: HTMLElement): void {
    const section = this.visibleGeneralSections().find((s) => s.id === id);
    if (!section) {
      return;
    }
    this.leaveCurrentView(() => {
      this.selectGeneral(section.id);
      this.revealEditor(menu, editor);
    });
  }

  protected goToHistory(menu: HTMLElement, editor: HTMLElement): void {
    this.leaveCurrentView(() => {
      this.selectGeneral('history');
      this.revealEditor(menu, editor);
    });
  }

  protected goToBranch(
    id: string,
    tab: BranchTabId = 'summary',
    menu?: HTMLElement,
    editor?: HTMLElement,
  ): void {
    const branch = this.branches().find((b) => b.id === id);
    if (!branch) {
      return;
    }
    this.leaveCurrentView(() => {
      this.openBranch(branch, tab);
      if (menu && editor) {
        this.revealEditor(menu, editor);
      }
    });
  }

  protected goToCoverage(
    branchId: string,
    coverageId: string,
    menu?: HTMLElement,
    editor?: HTMLElement,
  ): void {
    const branch = this.branches().find((b) => b.id === branchId);
    if (!branch) {
      return;
    }
    this.leaveCurrentView(() => {
      const tab = this.selectedCoverageId() == null ? 'data' : this.coverageTab();
      this.openCoverage(branch, coverageId, tab);
      if (menu && editor) {
        this.revealEditor(menu, editor);
      }
    });
  }

  /** A new coverage opens on its data, in the branch it belongs to. */
  protected addCoverageTo(branchId: string): void {
    const branch = this.branches().find((b) => b.id === branchId);
    if (!branch || this.readOnly()) {
      return;
    }
    this.leaveCurrentView(() => {
      const add = () => {
        const id = this.addCoverage();
        this.selectedCoverageId.set(id);
        this.showCoverageTab('data');
        this.applyFastTrackOf(id);
      };
      if (this.isBranchOpen(branchId)) {
        add();
        return;
      }
      this.select(branch);
      this.whenDetailLoaded(add);
    });
  }

  /** Loaded and shown: moving inside it needs no reload. */
  private isBranchOpen(id: string): boolean {
    return (
      this.view() === 'branch' &&
      this.selectedId() === id &&
      this.draft()?.id === id &&
      !this.detailFailed()
    );
  }

  private openBranch(branch: BranchRules, tab: BranchTabId): void {
    if (!this.isBranchOpen(branch.id)) {
      this.select(branch);
    }
    this.selectedCoverageId.set(null);
    this.ftCoverageId.set(null);
    this.showBranchTab(tab);
  }

  private openCoverage(branch: BranchRules, coverageId: string, tab: CoverageTabId): void {
    const wasOpen = this.isBranchOpen(branch.id);
    if (!wasOpen) {
      this.select(branch);
    }
    this.selectedCoverageId.set(coverageId);
    this.showCoverageTab(tab);
    this.applyFastTrackOf(coverageId);
    if (!wasOpen) {
      // Picked from a list read earlier: it may be gone by now.
      this.whenDetailLoaded(() => {
        if (!this.selectedCoverage()) {
          this.openBranch(branch, 'summary');
        }
      });
    }
  }

  /** With unsaved edits it asks first; "Descartar cambios" drops them and then moves. */
  private leaveCurrentView(navigate: () => void): void {
    if (this.hasUnsavedChanges()) {
      this.pendingNavigation.set(() => {
        this.discardAll();
        navigate();
      });
      return;
    }
    navigate();
  }

  private discardAll(): void {
    this.discardCommonExclusions();
    this.discardFastTrack();
    this.discardDocuments();
    this.discardBusinessRules();
    this.discardHardStop();
    this.discardCoverages();
  }

  protected discardAndLeave(): void {
    const navigate = this.pendingNavigation();
    this.pendingNavigation.set(null);
    navigate?.();
  }

  /** The tab strip already moved to the tab clicked: it goes back to the one still open. */
  protected keepEditing(): void {
    this.pendingNavigation.set(null);
    const strip = this.tabStrip();
    if (strip) {
      strip.value.set(this.level() === 'coverage' ? this.coverageTab() : this.branchTab());
    }
  }

  protected revealEditor(menu: HTMLElement, editor: HTMLElement): void {
    if (editor.offsetTop > menu.offsetTop) {
      editor.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }

  // ───────── Tabs ─────────

  private currentSlice(): Slice {
    switch (this.level()) {
      case 'branch':
        return this.branchTabSlice(this.branchTab());
      case 'coverage':
        return this.coverageTab() === 'fastTrack' ? 'fastTrack' : 'coverages';
      default:
        return null;
    }
  }

  private branchTabSlice(tab: BranchTabId): Slice {
    switch (tab) {
      // Where a removed coverage waits to be saved.
      case 'summary':
        return 'coverages';
      case 'exclusions':
        return 'commonExclusions';
      case 'documents':
        return 'documents';
      case 'rules':
        return 'businessRules';
      default:
        return null;
    }
  }

  private sliceDirty(slice: Slice): boolean {
    switch (slice) {
      case 'coverages':
        return this.covDirty();
      case 'commonExclusions':
        return this.exclDirty();
      case 'documents':
        return this.docDirty();
      case 'fastTrack':
        return this.ftDirty();
      case 'businessRules':
        return this.rulesDirty();
      default:
        return false;
    }
  }

  /** Tabs of one slice (the four of a coverage's own data) share its save bar: no question. */
  private leaveSlice(target: Slice, navigate: () => void): void {
    const current = this.currentSlice();
    if (current !== target && this.sliceDirty(current)) {
      this.pendingNavigation.set(() => {
        this.discardAll();
        navigate();
      });
      return;
    }
    navigate();
  }

  protected setBranchTab(id: string): void {
    const tab = this.branchTabs.find((t) => t.id === id);
    if (!tab || tab.id === this.branchTab()) {
      return;
    }
    this.leaveSlice(this.branchTabSlice(tab.id), () => this.showBranchTab(tab.id));
  }

  protected setCoverageTab(id: string): void {
    const tab = this.coverageTabs.find((t) => t.id === id);
    if (!tab || tab.id === this.coverageTab()) {
      return;
    }
    const target: Slice = tab.id === 'fastTrack' ? 'fastTrack' : 'coverages';
    this.leaveSlice(target, () => this.showCoverageTab(tab.id));
  }

  /**
   * A tab's first render is heavy enough to block the page, and until it ends the previous tab
   * stays painted. Showing the loader first, and rendering the tab after that frame is painted,
   * makes the switch visible at once.
   */
  protected readonly tabRendering = signal(false);
  private readonly renderedTabs = new Set<string>(['branch:summary', 'coverage:data']);

  private markRendering(key: string): void {
    if (!this.renderedTabs.has(key)) {
      this.renderedTabs.add(key);
      this.tabRendering.set(true);
      requestAnimationFrame(() => setTimeout(() => this.tabRendering.set(false)));
    }
  }

  private showBranchTab(id: BranchTabId): void {
    if (id !== this.branchTab()) {
      this.markRendering(`branch:${id}`);
    }
    this.branchTab.set(id);
  }

  private showCoverageTab(id: CoverageTabId): void {
    if (id !== this.coverageTab()) {
      this.markRendering(`coverage:${id}`);
    }
    this.coverageTab.set(id);
  }

  protected select(r: BranchRules): void {
    this.keepTreeCoverages();
    this.view.set('branch');
    this.selectedId.set(r.id);
    this.selectedCoverageId.set(null);
    this.expandBranch(r.id);
    this.draft.set(structuredClone(r));
    this.persisted.set(structuredClone(r));
    this.ftError.set(null);
    this.ftCoverageId.set(null);
    this.ftConfigs.set({});
    this.covError.set(null);
    this.exclError.set(null);
    this.docError.set(null);
    this.rulesError.set(null);
    this.renameError.set(null);
    this.editingName.set(false);
    this.beginDetailLoad(r.id);
    this.loadClaimCauses(r);
    this.loadDocumentTypes(r);
    this.loadCoveragesFromBackend(r);
    this.loadBusinessRulesFromBackend(r);
    this.settleDetailLoad();
  }

  private loadDocumentTypes(r: BranchRules): void {
    this.docTypes.set([]);
    const branchId = this.branchIdOf(r);
    if (branchId == null) {
      return;
    }
    this.trackDetail(r.id, this.documentTypesService.forBranch(branchId)).subscribe({
      next: (types) => this.docTypes.set(types),
      error: () => {
        /* best-effort: no document can be picked */
      },
    });
  }

  private loadClaimCauses(r: BranchRules): void {
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
   * One coverage's stored Fast Track. Reaches the screen only if that coverage is the one open
   * and its Fast Track has no unsaved edits: a late answer never lands on another coverage.
   */
  private loadFastTrackConfig(
    rulesId: string,
    branchId: number,
    coverageId: number,
    tracked: boolean,
  ): void {
    const source = this.ftService.getFastTrack(branchId, coverageId);
    (tracked ? this.trackDetail(rulesId, source) : source).subscribe({
      next: (dto) => {
        if (this.draft()?.id !== rulesId) {
          return;
        }
        this.ftConfigs.update((all) => ({ ...all, [String(coverageId)]: dto }));
        if (this.ftCoverageId() === coverageId && !this.ftDirty()) {
          this.overlayFastTrack(dto);
        }
      },
      error: () => {
        if (this.draft()?.id === rulesId) {
          this.ftConfigs.update((all) => ({ ...all, [String(coverageId)]: 'failed' }));
        }
      },
    });
  }

  /** Shows the coverage's Fast Track; a coverage not yet saved starts empty. */
  private applyFastTrackOf(coverageId: string): void {
    this.ftError.set(null);
    this.ftCoverageId.set(this.isPersistedId(coverageId) ? Number(coverageId) : null);
    const stored = this.ftConfigs()[coverageId];
    this.overlayFastTrack(stored === undefined || stored === 'failed' ? null : stored);
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
              requiresUpToDatePolicy: dto?.requiresUpToDatePolicy ?? true,
              requiredDocumentTypes: dto?.requiredDocumentTypes ?? [],
              criteria: dto?.criteria ?? [],
            },
          }
        : d,
    );
    this.ftPending.set(new Set());
    this.markPersisted('fastTrack');
  }

  /** Keeps a copy in `loadedCoverages` to diff creates/updates/deletes on save. */
  private loadCoveragesFromBackend(r: BranchRules): void {
    const branchId = this.branchIdOf(r);
    if (branchId == null) {
      return;
    }
    const rulesId = r.id;
    this.insurerCoverageNames.set(null);
    this.trackDetail(rulesId, this.coveragesService.insurerNames(branchId)).subscribe({
      next: (names) => this.insurerCoverageNames.set(names),
      error: () => this.insurerCoverageNames.set([]),
    });
    this.trackDetail(rulesId, this.coveragesService.listDetailed(branchId)).subscribe({
      next: (list) => {
        this.overlayCoverages(rulesId, list);
        this.loadCoverageExclusions(rulesId, list);
        this.loadCoverageHardRules(rulesId, list);
        list.forEach((c) => this.loadFastTrackConfig(rulesId, branchId, c.id, true));
      },
      error: () => {
        /* best-effort */
      },
    });
    this.trackDetail(rulesId, this.rulesTextService.getExclusions(branchId)).subscribe({
      next: (items) => {
        this.draft.update((d) => (d ? { ...d, commonExclusions: items } : d));
        this.markPersisted('commonExclusions');
      },
      error: () => {
        /* best-effort */
      },
    });
  }

  private overlayCoverages(rulesId: string, list: CoverageDetail[]): void {
    // On a reload of the same branch (after a save), keep what the screen shows until the fresh
    // exclusions and rules answer: starting them empty flashes the "covers the whole branch" warning.
    const current = this.draft();
    const shown = new Map(
      current && current.id === rulesId ? current.coverages.map((c) => [c.id, c]) : [],
    );
    const coverages: Coverage[] = list.map((c) => ({
      id: String(c.id),
      name: c.name,
      clause: c.clause ?? '',
      description: c.description ?? '',
      insuredAmount: null,
      deductibleRatio: c.deductibleRatio,
      deductibleBasis: c.deductibleBasis ?? 'SUM_INSURED',
      reportDeadlineHours: c.reportDeadlineHours,
      maxAnnualClaims: c.maxAnnualClaims,
      waitingPeriodDays: c.waitingPeriodDays,
      settlementFormula: c.settlementFormula ?? 'TOTAL_LOSS',
      settlementBasis: c.settlementBasis ?? 'SUM_INSURED',
      secondEventRatio: c.secondEventRatio,
      deductPendingInstallments: c.deductPendingInstallments,
      deductOverdueBalance: c.deductOverdueBalance,
      exclusions: c.exclusions ?? [],
      // Filled in by loadCoverageExclusions / loadCoverageHardRules (they live in rules-service).
      excludedClaimCauseIds: shown.get(String(c.id))?.excludedClaimCauseIds ?? [],
      hardRules: shown.get(String(c.id))?.hardRules ?? [],
    }));
    this.loadedCoverages = coverages;
    // Only if the draft still shows this branch: a slow reply for a branch the user left mustn't
    // clobber the screen.
    this.draft.update((d) => (d && d.id === rulesId ? { ...d, coverages } : d));
    this.markPersisted('coverages');
  }

  /** Best-effort and independent per coverage: if one fails, the others still load. */
  private loadCoverageExclusions(rulesId: string, list: CoverageDetail[]): void {
    list.forEach((c) => {
      this.trackDetail(rulesId, this.exclusionsService.get(c.id)).subscribe({
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
  private loadCoverageHardRules(rulesId: string, list: CoverageDetail[]): void {
    const draft = this.draft();
    const branchId = draft ? this.branchIdOf(draft) : null;
    if (branchId == null) {
      return;
    }
    list.forEach((c) => {
      this.trackDetail(rulesId, this.hardRulesService.get(branchId, c.id)).subscribe({
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

  private loadDocumentsFromBackend(r: BranchRules, claimCauseId: number): void {
    const branchId = this.branchIdOf(r);
    if (branchId == null) {
      return;
    }
    this.trackDetail(r.id, this.documentsService.get(branchId, claimCauseId)).subscribe({
      next: (items) => {
        const bands: Record<string, RiskBand> = {};
        items.forEach(({ documentType, minRiskBand }) => {
          if (minRiskBand) bands[documentType] = minRiskBand;
        });
        this.draft.update((d) =>
          d
            ? {
                ...d,
                requiredDocumentsByClaimCause: {
                  ...d.requiredDocumentsByClaimCause,
                  [claimCauseId]: items.map((item) => item.documentType),
                },
                documentBandsByClaimCause: {
                  ...d.documentBandsByClaimCause,
                  [claimCauseId]: bands,
                },
              }
            : d,
        );
        this.markPersisted('requiredDocumentsByClaimCause', 'documentBandsByClaimCause');
      },
      error: () => {
        /* best-effort */
      },
    });
  }

  private loadBusinessRulesFromBackend(r: BranchRules): void {
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

  /** Null while the selected branch has no id of its own yet. */
  protected readonly selectedBranchId = computed(() => {
    const d = this.draft();
    return d ? this.branchIdOf(d) : null;
  });

  private branchIdOf(r: BranchRules): number | null {
    const n = Number(r.id);
    return Number.isInteger(n) && n > 0 ? n : null;
  }

  protected setName(name: string): void {
    this.draft.update((d) => (d ? { ...d, name } : d));
  }

  protected startNameEdit(): void {
    this.renameError.set(null);
    this.editingName.set(true);
  }

  /** Puts the saved name back, so a half-typed one doesn't reappear on the next edit. */
  protected cancelNameEdit(): void {
    this.setName(this.renameFrom());
    this.renameError.set(null);
    this.editingName.set(false);
  }

  /** The saved name: the draft already holds what was typed. */
  protected readonly renameFrom = computed(
    () => this.branches().find((r) => r.id === this.selectedId())?.name ?? '',
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
    this.renaming.set(true);
    this.branchesService.rename(branchId, name).subscribe({
      next: (branch) => {
        // The editor closes once the button has shown "Guardado" (saveSettled).
        this.renaming.set(false);
        this.branches.update((list) =>
          list.map((r) => (r.id === String(branch.id) ? { ...r, name: branch.name } : r)),
        );
      },
      error: (e: unknown) => {
        this.renaming.set(false);
        this.renameError.set(this.backendErrorMessage(e));
      },
    });
  }

  /** Adds it to the open branch's draft and returns its temporary id. */
  private addCoverage(): string {
    const coverage: Coverage = {
      id: `cov-${Date.now()}`,
      name: '',
      clause: '',
      description: '',
      insuredAmount: null,
      deductibleRatio: null,
      deductibleBasis: 'SUM_INSURED',
      reportDeadlineHours: null,
      maxAnnualClaims: null,
      waitingPeriodDays: null,
      // Default: ceiling = sum insured, no deductions beyond the deductible. Enabling deductions
      // changes what the insured gets paid, so it must be an explicit choice.
      settlementFormula: 'TOTAL_LOSS',
      settlementBasis: 'SUM_INSURED',
      secondEventRatio: null,
      deductPendingInstallments: false,
      deductOverdueBalance: false,
      exclusions: [],
      excludedClaimCauseIds: [],
      // Every rule, switched off: a new coverage is configured whole before its first save.
      hardRules: RulesComponent.HARD_RULE_ORDER.map((ruleType) => ({
        ruleType,
        enabled: false,
        deadlineHours: null,
      })),
    };
    this.draft.update((d) => (d ? { ...d, coverages: [...d.coverages, coverage] } : d));
    return coverage.id;
  }

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

  /** `OFF` isn't an `onArrears` value: it is the rule switched off (`enabled=false`). */
  protected readonly onArrearsOptions: SelectOption[] = [
    { value: 'REJECT', label: 'No dejar denunciar' },
    { value: 'STANDBY', label: 'Dejar denunciar' },
    { value: 'OFF', label: 'No controlar' },
  ];

  protected policyStandingChoice(): string {
    const rule = this.policyStandingRule();
    return rule?.enabled ? (rule.onArrears ?? 'STANDBY') : 'OFF';
  }

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

  protected setOnArrears(value: string): void {
    this.insurerHardRules.update((list) =>
      list.map((r) => {
        if (r.ruleType !== 'POLICY_STANDING') {
          return r;
        }
        // Switching it off keeps the last choice, so turning it back on restores it.
        return value === 'OFF'
          ? { ...r, enabled: false }
          : { ...r, enabled: true, onArrears: value as OnArrears };
      }),
    );
  }

  /** A link on a locked Fast Track block, to the screen where that rule is set. */
  protected openGeneralView(section: GeneralView): void {
    this.leaveCurrentView(() => this.selectGeneral(section));
  }

  protected policyStandingRule(): InsurerHardRule | undefined {
    return this.insurerHardRules().find((r) => r.ruleType === 'POLICY_STANDING');
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

  /** Stored as a deny-list; the panel shows its complement, which is what the referent thinks in. */
  protected isCauseCovered(c: Coverage, causeId: number): boolean {
    return !(c.excludedClaimCauseIds ?? []).includes(causeId);
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

  /** The coverage waiting for the removal confirmation. */
  protected readonly coverageToRemove = signal<Coverage | null>(null);

  protected confirmRemoveCoverage(): void {
    const c = this.coverageToRemove();
    this.coverageToRemove.set(null);
    if (c) {
      this.removeCoverage(c.id);
    }
  }

  /** The branch summary takes its place; the removal waits there for the save. */
  private removeCoverage(id: string): void {
    this.draft.update((d) => (d ? { ...d, coverages: d.coverages.filter((c) => c.id !== id) } : d));
    if (this.selectedCoverageId() === id) {
      this.selectedCoverageId.set(null);
      this.ftCoverageId.set(null);
      this.showBranchTab('summary');
    }
  }

  /** Names on the insurer's policies of the open branch; null while they load. */
  protected readonly insurerCoverageNames = signal<string[] | null>(null);

  /** The insurer's names not taken by another coverage of the branch, plus this one's own. */
  protected coverageNameOptions(c: Coverage): SelectOption[] {
    const taken = new Set(
      (this.draft()?.coverages ?? []).filter((o) => o.id !== c.id).map((o) => o.name),
    );
    const names = (this.insurerCoverageNames() ?? []).filter((name) => !taken.has(name));
    // A name stored before this selector existed stays visible, so the mismatch can be fixed.
    if (c.name && !names.includes(c.name)) {
      names.unshift(c.name);
    }
    return names.map((name) => ({ value: name, label: name }));
  }

  /** Named something no policy of the branch has: no claim could be filed under it. */
  protected coverageNameUnmatched(c: Coverage): boolean {
    const names = this.insurerCoverageNames();
    return names !== null && c.name !== '' && !names.includes(c.name);
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

  protected setCoverageReportDeadline(id: string, value: string): void {
    this.setCoverageField(id, { reportDeadlineHours: this.intFromStr(value) });
  }

  protected setCoverageMaxClaims(id: string, value: string): void {
    this.setCoverageField(id, { maxAnnualClaims: this.intFromStr(value) });
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

  /** Summary on the branch's coverage cards; unset values are omitted rather than shown as "—". */
  protected coverageSummary(c: Coverage): string[] {
    const chips: string[] = [];
    if (c.deductibleRatio != null) {
      chips.push(
        `Franquicia ${this.pctFromRatio(c.deductibleRatio)}%` +
          (c.deductibleBasis === 'LOSS_AMOUNT' ? ' del monto a indemnizar' : ''),
      );
    }
    if (c.waitingPeriodDays != null && !this.hardRuleOff(c, 'WAITING_PERIOD')) {
      chips.push(`Carencia ${c.waitingPeriodDays} d`);
    }
    if (c.reportDeadlineHours != null && !this.hardRuleOff(c, 'REPORT_DEADLINE')) {
      chips.push(`Denuncia ${c.reportDeadlineHours} h`);
    }
    const police = this.hardRulesOf(c).find((r) => r.ruleType === 'POLICE_DEADLINE');
    if (police?.deadlineHours != null && police.enabled && this.coversACauseWithPoliceReport(c)) {
      chips.push(`Policial ${police.deadlineHours} h`);
    }
    if (c.maxAnnualClaims != null && !this.hardRuleOff(c, 'MAX_EVENTS_YEAR')) {
      chips.push(`Máx. ${c.maxAnnualClaims}/año`);
    }
    // Covering everything already has its own badge; an unloaded catalog would read as "none".
    const causes = this.claimCauses();
    if (this.hasExcludedCauses(c) && causes.length > 0) {
      const covered = causes.filter((cause) => this.isCauseCovered(c, cause.id));
      chips.push(
        covered.length > 0
          ? `Cubre ${covered.map((cause) => cause.name).join(', ')}`
          : 'No cubre ningún hecho',
      );
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

  /** The police deadline is left out when the coverage has no police report to time. */
  protected orderedHardRules(c: Coverage): HardRule[] {
    const rules = this.hardRulesOf(c);
    const policeReportApplies = this.coversACauseWithPoliceReport(c);
    return RulesComponent.HARD_RULE_ORDER.filter(
      (type) => type !== 'POLICE_DEADLINE' || policeReportApplies,
    )
      .map((type) => rules.find((r) => r.ruleType === type))
      .filter((r): r is HardRule => r != null);
  }

  /** The waiting period stops the claim at intake; the rest only take Fast Track away. */
  protected rejectingHardRules(c: Coverage): HardRule[] {
    return this.orderedHardRules(c).filter((r) => r.ruleType === 'WAITING_PERIOD');
  }

  protected fastTrackHardRules(c: Coverage): HardRule[] {
    return this.orderedHardRules(c).filter((r) => r.ruleType !== 'WAITING_PERIOD');
  }

  /**
   * Read off the drafts of both tabs, so the rule shows up as the referent picks the covered causes
   * or edits the document schedule. The engine applies the same test to each claim.
   */
  private coversACauseWithPoliceReport(c: Coverage): boolean {
    return this.claimCauses().some(
      (cause) =>
        this.isCauseCovered(c, cause.id) && this.isDocRequiredFor(cause.id, 'police_report'),
    );
  }

  protected hardRuleUnit(type: HardRuleType): string {
    if (type === 'MAX_EVENTS_YEAR') {
      return 'por año';
    }
    return type === 'WAITING_PERIOD' ? 'días' : 'horas';
  }

  private hardRuleOff(c: Coverage, type: HardRuleType): boolean {
    return this.hardRulesOf(c).some((r) => r.ruleType === type && !r.enabled);
  }

  protected hardRuleOffNote(type: HardRuleType): string {
    if (type === 'MAX_EVENTS_YEAR') {
      return 'Sin tope';
    }
    return type === 'WAITING_PERIOD' ? 'Sin carencia' : 'Sin plazo';
  }

  /** Three thresholds are coverage columns; the police deadline lives on the rule itself. */
  /** Switched on with no threshold: the engine would skip it while the screen shows it as active. */
  protected ruleMissingValue(c: Coverage, rule: HardRule): boolean {
    return rule.enabled && this.hardRuleValue(c, rule.ruleType).trim() === '';
  }

  protected readonly covCanSave = computed(
    () =>
      !(this.draft()?.coverages ?? []).some(
        (c) =>
          c.name.trim() === '' ||
          this.hardRulesOf(c).some((rule) => this.ruleMissingValue(c, rule)),
      ),
  );

  protected hardRuleValue(c: Coverage, type: HardRuleType): string {
    switch (type) {
      case 'WAITING_PERIOD':
        return this.intStr(c.waitingPeriodDays);
      case 'REPORT_DEADLINE':
        return this.intStr(c.reportDeadlineHours);
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
        this.setCoverageReportDeadline(c.id, value);
        return;
      case 'POLICE_DEADLINE':
        this.setPoliceDeadline(c.id, value);
        return;
      case 'MAX_EVENTS_YEAR':
        this.setCoverageMaxClaims(c.id, value);
        return;
    }
  }

  protected readonly settlementFormulaOptions: SelectOption[] = [
    { value: 'TOTAL_LOSS', label: 'Pérdida total' },
    { value: 'REPAIR', label: 'Reparación' },
  ];

  protected setCoverageSettlementFormula(id: string, value: string): void {
    this.setCoverageField(id, { settlementFormula: value as SettlementFormula });
  }

  /** Only for total loss: in a repair the ceiling is the quote. */
  protected showsSettlementBasis(c: Coverage): boolean {
    return c.settlementFormula !== 'REPAIR';
  }

  // Short enough to read whole in the select.
  protected readonly settlementBasisOptions: SelectOption[] = [
    { value: 'SUM_INSURED', label: 'Suma asegurada' },
    { value: 'LESSER_OF_SUM_AND_REPLACEMENT', label: 'Menor entre suma y reposición' },
  ];

  protected setCoverageSettlementBasis(id: string, value: string): void {
    this.setCoverageField(id, { settlementBasis: value as SettlementBasis });
  }

  protected readonly deductibleBasisOptions: SelectOption[] = [
    { value: 'SUM_INSURED', label: 'Suma asegurada' },
    { value: 'LOSS_AMOUNT', label: 'Monto a indemnizar' },
  ];

  protected setCoverageDeductibleBasis(id: string, value: string): void {
    this.setCoverageField(id, { deductibleBasis: value as DeductibleBasis });
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
    const restored = this.ftLastValues.get(field) ?? RulesComponent.FT_DEFAULTS[field];
    if (field === 'maxClaimedAmountRatio') {
      this.setFtMaxRatio(String(restored));
    } else {
      this.patchFastTrack((c) => ({ ...c, [field]: restored }));
    }
  }

  protected setMinPolicyAge(months: number | null): void {
    this.keepFtCondition('age');
    this.patchFastTrack((ft) => ({ ...ft, minPolicyAgeMonths: wholeMonths(months) }));
  }

  protected setMaxPriorClaims(v: string): void {
    this.keepFtCondition('prior');
    this.patchFastTrack((ft) => ({ ...ft, maxPriorClaims: this.intFromStr(v) }));
  }

  protected setPriorClaimsWindow(months: number | null): void {
    this.patchFastTrack((ft) => ({ ...ft, priorClaimsWindowMonths: wholeMonths(months) }));
  }

  protected setFtMaxRatio(v: string): void {
    this.keepFtCondition('amount');
    this.patchFastTrack((ft) => ({ ...ft, maxClaimedAmountRatio: this.ratioFromPct(v) }));
  }

  /**
   * Conditions the referent added or is editing, kept on screen while their value is empty: a
   * block that vanished on clearing its field would lose the referent's place. Value present means
   * shown anyway; this only covers the empty ones.
   */
  protected readonly ftPending = signal<ReadonlySet<FtCondition>>(new Set());

  private keepFtCondition(id: FtCondition): void {
    if (!this.ftPending().has(id)) {
      this.ftPending.update((all) => new Set(all).add(id));
    }
  }

  /** What the engine evaluates: one block, one active condition (FastTrackValidator). */
  private ftHasValue(id: FtCondition): boolean {
    const ft = this.draft()?.fastTrack;
    if (!ft) {
      return false;
    }
    switch (id) {
      case 'age':
        return ft.minPolicyAgeMonths != null;
      case 'prior':
        return ft.maxPriorClaims != null;
      case 'amount':
        return ft.maxClaimedAmountRatio != null;
      case 'upToDate':
        return ft.requiresUpToDatePolicy === true;
      case 'docs':
        return ft.requiredDocumentTypes.length > 0;
    }
  }

  protected ftShown(id: FtCondition): boolean {
    return this.ftHasValue(id) || this.ftPending().has(id);
  }

  /** On screen with nothing to evaluate: the engine would skip it, so it can't be saved that way. */
  protected ftMissing(id: FtCondition): boolean {
    return this.ftPending().has(id) && !this.ftHasValue(id);
  }

  protected readonly ftAddOptions = computed<MenuItem[]>(() =>
    FT_CONDITIONS.filter((c) => !this.ftShown(c.id)).map((c) => ({
      value: c.id,
      label: c.label,
    })),
  );

  protected readonly ftEmpty = computed(() => FT_CONDITIONS.every((c) => !this.ftShown(c.id)));

  /** No conditions at all would make every claim Fast Track, so it can't be saved. */
  protected readonly ftCanSave = computed(
    () =>
      !this.ftHasStaleDocs() &&
      !this.ftEmpty() &&
      FT_CONDITIONS.every((c) => !this.ftMissing(c.id)),
  );

  protected addFtCondition(value: string): void {
    const id = value as FtCondition;
    switch (id) {
      case 'age':
        this.toggleFtThreshold('minPolicyAgeMonths');
        return;
      case 'prior':
        this.toggleFtThreshold('maxPriorClaims');
        return;
      case 'amount':
        this.toggleFtThreshold('maxClaimedAmountRatio');
        return;
      case 'upToDate':
        this.patchFastTrack((ft) => ({ ...ft, requiresUpToDatePolicy: true }));
        return;
      case 'docs':
        this.keepFtCondition('docs');
        return;
    }
  }

  protected removeFtCondition(id: FtCondition): void {
    this.ftPending.update((all) => {
      const next = new Set(all);
      next.delete(id);
      return next;
    });
    switch (id) {
      case 'age':
        this.clearFtThreshold('minPolicyAgeMonths');
        return;
      case 'prior':
        this.clearFtThreshold('maxPriorClaims');
        return;
      case 'amount':
        this.clearFtThreshold('maxClaimedAmountRatio');
        return;
      case 'upToDate':
        this.patchFastTrack((ft) => ({ ...ft, requiresUpToDatePolicy: false }));
        return;
      case 'docs':
        this.patchFastTrack((ft) => ({ ...ft, requiredDocumentTypes: [] }));
        return;
    }
  }

  private clearFtThreshold(
    field: 'minPolicyAgeMonths' | 'maxPriorClaims' | 'maxClaimedAmountRatio',
  ): void {
    if (this.draft()?.fastTrack[field] != null) {
      this.toggleFtThreshold(field);
    }
  }

  /** Read from the screen being edited: what applies to this coverage right now. */
  protected readonly ftTermsApply = computed(() => {
    const c = this.selectedCoverage();
    return (
      c != null &&
      this.orderedHardRules(c).some((r) => r.enabled && r.ruleType !== 'WAITING_PERIOD')
    );
  });

  /** With "Dejar denunciar", arrears takes Fast Track away (TemporalRuleEvaluator). */
  protected readonly ftArrearsApplies = computed(() => {
    const rule = this.policyStandingRule();
    return rule?.enabled === true && rule.onArrears !== 'REJECT' && !this.ftShown('upToDate');
  });

  protected readonly fraudBlocksFastTrack = signal(false);

  /**
   * Fast Track asks for the first round of the coverage's own documents: those the document
   * schedule requires for the claim causes the coverage covers. A document already chosen but no
   * longer in that list stays listed, so it can be turned off.
   */
  protected readonly ftDocOptions = computed(() => {
    const d = this.draft();
    const coverageId = this.ftCoverageId();
    if (!d || coverageId == null) {
      return [];
    }
    const coverage = d.coverages.find((c) => c.id === String(coverageId));
    const excluded = new Set(coverage?.excludedClaimCauseIds ?? []);
    const allowed = new Set<string>();
    this.claimCauses()
      .filter((cause) => !excluded.has(cause.id))
      .forEach((cause) =>
        (d.requiredDocumentsByClaimCause[cause.id] ?? []).forEach((code) => allowed.add(code)),
      );
    return this.docTypes()
      .filter((doc) => allowed.has(doc.code) || this.isFtDoc(doc.code))
      .map((doc) => ({ ...doc, outsideSchedule: !allowed.has(doc.code) }));
  });

  protected readonly ftHasStaleDocs = computed(() =>
    this.ftDocOptions().some((doc) => doc.outsideSchedule),
  );

  protected isFtDoc(code: string): boolean {
    return this.draft()?.fastTrack.requiredDocumentTypes.includes(code) ?? false;
  }

  protected toggleFtDoc(code: string): void {
    this.keepFtCondition('docs');
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

  protected ftMaxPriorStr(): string {
    return this.intStr(this.draft()?.fastTrack.maxPriorClaims);
  }
  protected ftMaxRatioPct(): string {
    return this.pctFromRatio(this.draft()?.fastTrack.maxClaimedAmountRatio ?? null);
  }

  protected setBusinessRules(items: string[]): void {
    this.patch({ businessRules: items });
  }

  /** Only the open coverage: each has its own FAST_TRACK rule. */
  protected saveFastTrack(): void {
    const d = this.draft();
    if (!d || this.ftSaving() || !this.ftCanSave()) {
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
      this.ftError.set('Guardá la cobertura antes de configurar su Fast Track.');
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
        this.loadFastTrackConfig(d.id, branchId, coverageId, false);
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
    if (!d || this.covSaving() || !this.covCanSave()) {
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
      ...toUpdate.map((c) =>
        forkJoin([
          this.coveragesService.update(Number(c.id), this.toCoverageRequest(c)),
          this.saveCoverageRules(branchId, Number(c.id), c),
        ]),
      ),
      // Rules need the coverage's real id, so they wait for the create.
      ...toCreate.map((c) =>
        this.coveragesService.create(branchId, this.toCoverageRequest(c)).pipe(
          tap((created) => this.adoptCreatedCoverage(c.id, created.id)),
          switchMap((created) => this.saveCoverageRules(branchId, created.id, c)),
        ),
      ),
    ];

    this.covSaving.set(true);
    forkJoin(requests.length ? requests : [of(null)]).subscribe({
      next: () => {
        this.covSaving.set(false);
        this.markPersisted('coverages');
        // Reload so the screen shows exactly what was stored.
        this.loadCoveragesFromBackend(d);
        this.refreshCoverageSummary();
      },
      error: (e: unknown) => {
        this.covSaving.set(false);
        this.covError.set(this.backendErrorMessage(e));
      },
    });
  }

  private saveCoverageRules(
    branchId: number,
    coverageId: number,
    c: Coverage,
  ): Observable<unknown> {
    return forkJoin([
      this.exclusionsService.save(branchId, coverageId, c.excludedClaimCauseIds ?? []),
      this.hardRulesOf(c).length > 0
        ? this.hardRulesService.save(branchId, coverageId, this.hardRulesOf(c))
        : of(null),
    ]);
  }

  /**
   * Swaps the draft's temporary id for the stored one as soon as the create answers, so a retry
   * after a later failure updates the coverage instead of creating it twice.
   */
  private adoptCreatedCoverage(tempId: string, storedId: number): void {
    const id = String(storedId);
    this.draft.update((d) =>
      d ? { ...d, coverages: d.coverages.map((c) => (c.id === tempId ? { ...c, id } : c)) } : d,
    );
    const created = this.draft()?.coverages.find((c) => c.id === id);
    if (created) {
      this.loadedCoverages = [...this.loadedCoverages, structuredClone(created)];
    }
    if (this.selectedCoverageId() === tempId) {
      this.selectedCoverageId.set(id);
      this.ftCoverageId.set(storedId);
    }
  }

  private toCoverageRequest(c: Coverage): CoverageUpsertRequest {
    return {
      name: c.name,
      clause: c.clause || null,
      description: c.description.trim() || null,
      deductibleRatio: c.deductibleRatio,
      deductibleBasis: c.deductibleBasis,
      reportDeadlineHours: c.reportDeadlineHours,
      maxAnnualClaims: c.maxAnnualClaims,
      waitingPeriodDays: c.waitingPeriodDays,
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
          (d.requiredDocumentsByClaimCause[claimCauseId] ?? []).map((documentType) => ({
            documentType,
            minRiskBand: d.documentBandsByClaimCause[claimCauseId]?.[documentType] ?? null,
          })),
        ),
      ),
    ).subscribe({
      next: () => {
        this.docSaving.set(false);
        this.markPersisted('requiredDocumentsByClaimCause', 'documentBandsByClaimCause');
        changed.forEach((claimCauseId) => this.loadDocumentsFromBackend(d, claimCauseId));
      },
      error: (e: unknown) => {
        this.docSaving.set(false);
        this.docError.set(this.backendErrorMessage(e));
      },
    });
  }

  protected isDocRequiredFor(claimCauseId: number, code: string): boolean {
    return this.draft()?.requiredDocumentsByClaimCause[claimCauseId]?.includes(code) ?? false;
  }

  /**
   * One choice per cell: whether the document is asked for and from which risk band. `NONE` is no
   * row and `ALWAYS` a row without band. LOW is left out: from Bajo up reads the same as "always".
   */
  protected readonly docRequirementOptions: SelectOption[] = [
    { value: 'NONE', label: 'No se pide' },
    { value: 'ALWAYS', label: 'Siempre' },
    { value: 'MEDIUM', label: 'Riesgo medio o más' },
    { value: 'HIGH', label: 'Riesgo alto o más' },
    { value: 'CRITICAL', label: 'Solo riesgo crítico' },
  ];

  protected docRequirement(claimCauseId: number, code: string): string {
    if (!this.isDocRequiredFor(claimCauseId, code)) {
      return 'NONE';
    }
    return this.draft()?.documentBandsByClaimCause[claimCauseId]?.[code] ?? 'ALWAYS';
  }

  protected setDocRequirement(claimCauseId: number, code: string, value: string): void {
    this.draft.update((d) => {
      if (!d) {
        return d;
      }
      const current = d.requiredDocumentsByClaimCause[claimCauseId] ?? [];
      const asked = value !== 'NONE';
      // Kept in place when only the band changes, so the stored order doesn't read as a change.
      const required = asked
        ? current.includes(code)
          ? current
          : [...current, code]
        : current.filter((c) => c !== code);
      const bands = { ...(d.documentBandsByClaimCause[claimCauseId] ?? {}) };
      if (asked && value !== 'ALWAYS') {
        bands[code] = value as RiskBand;
      } else {
        delete bands[code];
      }
      return {
        ...d,
        requiredDocumentsByClaimCause: {
          ...d.requiredDocumentsByClaimCause,
          [claimCauseId]: required,
        },
        documentBandsByClaimCause: { ...d.documentBandsByClaimCause, [claimCauseId]: bands },
      };
    });
  }

  protected requiredCountLabel(claimCauseId: number): string {
    const count = this.draft()?.requiredDocumentsByClaimCause[claimCauseId]?.length ?? 0;
    if (count === 0) {
      return 'Sin documentos';
    }
    return count === 1 ? '1 documento' : `${count} documentos`;
  }

  /** Only changed agendas are saved: resending all would overwrite others' concurrent edits. */
  private changedClaimCauses(): number[] {
    const current = this.draft()?.requiredDocumentsByClaimCause ?? {};
    const base = this.persisted()?.requiredDocumentsByClaimCause ?? {};
    const currentBands = this.draft()?.documentBandsByClaimCause ?? {};
    const baseBands = this.persisted()?.documentBandsByClaimCause ?? {};
    return this.claimCauses()
      .map((c) => c.id)
      .filter(
        (id) =>
          JSON.stringify(current[id] ?? []) !== JSON.stringify(base[id] ?? []) ||
          JSON.stringify(currentBands[id] ?? {}) !== JSON.stringify(baseBands[id] ?? {}),
      );
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

  private patch(partial: Partial<BranchRules>): void {
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
