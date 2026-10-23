import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';

import { RISK_FACTORS, ScoringConfig } from '../../../core/models/business-rules';
import { RISK_BANDS, RiskBand, riskBandLabel } from '../../../core/models/risk-band';
import { formatRate } from '../../../core/util/percent';
import { ScoringConfigDto, ScoringRulesService } from '../scoring-rules.service';
import { SwitchComponent } from '../../../shared/ui/switch/switch.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { SaveBarComponent } from '../../../shared/ui/save-bar/save-bar.component';
import { InfoTipComponent } from '../../../shared/ui/info-tip/info-tip.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { fadeInUp } from '../../../shared/animations';
import { ONBOARDING_STEP_LINE } from '../../../core/onboarding/onboarding';
import { OnboardingTargetDirective } from '../../../core/onboarding/onboarding-target.directive';

/**
 * One scoring config per insurer, not per branch, hence outside the branch master-detail.
 * The UI shows percentages (0..100); the backend contract uses fractions (0..1).
 */
@Component({
  selector: 'app-scoring-config',
  imports: [
    OnboardingTargetDirective,
    SwitchComponent,
    CardComponent,
    InfoTipComponent,
    InputComponent,
    InlineLoadingComponent,
    SaveBarComponent,
    ButtonComponent,
  ],
  animations: [fadeInUp],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './scoring-config.component.html',
  styleUrl: './scoring-config.component.scss',
})
export class ScoringConfigComponent {
  protected readonly stepLine = ONBOARDING_STEP_LINE;

  private readonly scoringService = inject(ScoringRulesService);

  protected readonly riskFactors = RISK_FACTORS;
  protected readonly riskBands = RISK_BANDS;
  protected readonly bandLabel = riskBandLabel;

  protected readonly draft = signal<ScoringConfig>(this.skeleton());

  // The component is recreated on every visit (`@if` in rules.component.html), so `load()` reruns;
  // without this flag the empty skeleton would show until the response arrives.
  protected readonly loading = signal(true);
  protected readonly loadFailed = signal(false);

  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Last state confirmed by the backend; unsaved changes are measured against it. */
  private readonly persisted = signal<ScoringConfig | null>(null);

  protected readonly dirty = computed(() => {
    const base = this.persisted();
    return base != null && JSON.stringify(this.draft()) !== JSON.stringify(base);
  });

  protected discard(): void {
    const base = this.persisted();
    if (base) {
      this.draft.set(structuredClone(base));
    }
    this.error.set(null);
    this.load();
  }

  private readonly weightPcts = computed(() =>
    this.draft().factors.map((f) => this.weightPctOf(f.weight)),
  );

  protected readonly hasActiveFactors = computed(() => this.weightPcts().length > 0);

  private readonly totalPct = computed(() => this.weightPcts().reduce((sum, pct) => sum + pct, 0));

  protected readonly totalLabel = computed(() => formatRate(this.totalPct() / 100, 2));

  protected readonly totalOff = computed(() => this.totalPct() !== 100);

  protected readonly weightsInvalidReason = computed<string | null>(() => {
    const pcts = this.weightPcts();
    if (pcts.length === 0) {
      return null;
    }
    if (pcts.some((pct) => pct <= 0)) {
      return 'Cada factor prendido necesita un peso mayor que 0%.';
    }
    if (pcts.some((pct) => !Number.isInteger(pct))) {
      return 'Los pesos van en porcentajes enteros.';
    }
    return this.totalPct() === 100 ? null : 'Los pesos tienen que sumar 100%.';
  });

  /**
   * Grouped by cost: data factors always run; document and image factors need heavy analysis and may
   * be skipped on Fast Track (see `fullAnalysisOnFastTrack`).
   */
  protected readonly factorGroups: {
    title: string;
    hint: string;
    ids: string[];
    heavy?: boolean;
  }[] = [
    {
      title: 'Datos del siniestro y del asegurado',
      hint: '',
      ids: [
        'amount_ratio',
        'claim_frequency',
        'policy_standing',
        'purchase_to_report_time',
        'fraud_history',
      ],
    },
    {
      title: 'Documentos e imágenes',
      hint: '',
      ids: ['document_inconsistency', 'image_reuse', 'image_web_match'],
      heavy: true,
    },
  ];

  protected factorsOf(group: { ids: string[] }): { id: string; label: string }[] {
    return group.ids
      .map((id) => this.riskFactors.find((f) => f.id === id))
      .filter((f): f is { id: string; label: string } => f != null);
  }

  protected bandRangeLabel(band: RiskBand): string {
    const cuts = RISK_BANDS.map((b) => this.bandCutValue(b));
    const index = RISK_BANDS.indexOf(band);
    const from = cuts[index];
    const next = cuts[index + 1];
    // The last band reaches 100; the others end right before the next one starts.
    return next == null ? `Puntaje ${from}–100` : `Puntaje ${from}–${Math.max(from, next - 1)}`;
  }

  private bandCutValue(band: RiskBand): number {
    if (band === 'LOW') {
      return 0;
    }
    const cut = this.draft().bands.find((b) => b.band === band);
    return cut ? Math.round(cut.minScoreInclusive * 100) : 0;
  }

  protected bandWidth(band: RiskBand): number {
    const index = RISK_BANDS.indexOf(band);
    const from = this.bandCutValue(band);
    const next = RISK_BANDS[index + 1];
    const to = next ? this.bandCutValue(next) : 100;
    return Math.max(0, to - from);
  }

  /** Same tones as app-fraud-gauge, so the referente sees what the analyst will. */
  protected bandTone(band: RiskBand): string {
    switch (band) {
      case 'LOW':
        return 'ok';
      case 'MEDIUM':
        return 'warning';
      case 'HIGH':
        return 'risk';
      case 'CRITICAL':
        return 'danger';
    }
  }

  constructor() {
    this.load(true);
  }

  /** No factors and the 4 default bands: the engine scores whenever there are factors. */
  private skeleton(): ScoringConfig {
    return {
      fullAnalysisOnFastTrack: false,
      factors: [],
      bands: RISK_BANDS.map((band, i) => ({ band, minScoreInclusive: [0, 0.3, 0.6, 0.8][i] })),
    };
  }

  /**
   * An unconfigured insurer gets an empty config (no bands), filled with the defaults. A failed load
   * shows an error instead of the skeleton, which would read as every factor switched off.
   * `showLoading` only on first load: the post-save reload already has the button's indicator and
   * would otherwise flicker.
   */
  protected load(showLoading = false): void {
    if (showLoading) {
      this.loading.set(true);
      this.loadFailed.set(false);
    }
    // With unsaved changes a refresh stays in the cache; discarding picks it up (L1).
    this.scoringService
      .get({ apply: () => !this.dirty() })
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (dto) => {
          const bands = dto.bands.length ? dto.bands : this.skeleton().bands;
          const loaded: ScoringConfig = {
            fullAnalysisOnFastTrack: dto.fullAnalysisOnFastTrack ?? false,
            factors: dto.factors,
            bands,
          };
          this.draft.set(loaded);
          this.persisted.set(structuredClone(loaded));
        },
        error: () => {
          if (this.persisted() === null) {
            this.loadFailed.set(true);
          }
        },
      });
  }

  protected isFactorActive(id: string): boolean {
    return this.draft().factors.some((f) => f.factorId === id);
  }

  protected toggleFactor(id: string): void {
    this.patch((sc) => {
      const has = sc.factors.some((f) => f.factorId === id);
      return {
        ...sc,
        factors: has
          ? sc.factors.filter((f) => f.factorId !== id)
          : [...sc.factors, { factorId: id, weight: 0 }],
      };
    });
  }

  protected fullAnalysisOnFastTrack(): boolean {
    return this.draft().fullAnalysisOnFastTrack;
  }

  protected toggleFullAnalysisOnFastTrack(): void {
    this.patch((sc) => ({ ...sc, fullAnalysisOnFastTrack: !sc.fullAnalysisOnFastTrack }));
  }

  protected factorWeightPct(id: string): string {
    const f = this.draft().factors.find((x) => x.factorId === id);
    return f ? String(this.weightPctOf(f.weight)) : '';
  }

  protected setFactorWeight(id: string, value: string): void {
    const ratio = this.ratioFromPct(value) ?? 0;
    this.patch((sc) => ({
      ...sc,
      factors: sc.factors.map((f) => (f.factorId === id ? { ...f, weight: ratio } : f)),
    }));
  }

  protected bandCutPct(band: RiskBand): string {
    const b = this.draft().bands.find((x) => x.band === band);
    return b ? this.pctFromRatio(b.minScoreInclusive) : '';
  }

  protected setBandCut(band: RiskBand, value: string): void {
    const ratio = this.ratioFromPct(value) ?? 0;
    this.patch((sc) => ({
      ...sc,
      bands: sc.bands.map((b) => (b.band === band ? { ...b, minScoreInclusive: ratio } : b)),
    }));
  }

  protected saveScoring(): void {
    if (this.saving() || this.weightsInvalidReason()) {
      return;
    }
    this.error.set(null);
    const sc = this.draft();
    const dto: ScoringConfigDto = {
      fullAnalysisOnFastTrack: sc.fullAnalysisOnFastTrack,
      factors: sc.factors,
      bands: sc.bands,
    };

    this.saving.set(true);
    this.scoringService.save(dto).subscribe({
      next: () => {
        this.saving.set(false);
        this.load();
      },
      error: (e: unknown) => {
        this.saving.set(false);
        this.error.set(this.backendErrorMessage(e));
      },
    });
  }

  private patch(fn: (sc: ScoringConfig) => ScoringConfig): void {
    this.draft.update(fn);
  }

  private backendErrorMessage(e: unknown): string {
    if (e instanceof HttpErrorResponse) {
      if (e.status === 403) {
        return 'No tenés permiso para editar el puntaje de riesgo (se requiere rol Referente).';
      }
      if (e.status === 0) {
        return 'No se pudo contactar al backend de reglas (¿el servicio está arriba?).';
      }
      const detail = (e.error as { detail?: string } | null)?.detail;
      return detail ?? `El backend rechazó el guardado (${e.status}).`;
    }
    return e instanceof Error ? e.message : 'No se pudo guardar.';
  }

  private weightPctOf(weight: number): number {
    return Math.round(weight * 10000) / 100;
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
}
