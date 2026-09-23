import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';

import { RISK_FACTORS, ScoringConfig } from '../../../core/models/business-rules';
import { RISK_BANDS, RiskBand, riskBandLabel } from '../../../core/models/risk-band';
import { ScoringConfigDto, ScoringRulesService } from '../scoring-rules.service';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { SwitchComponent } from '../../../shared/ui/switch/switch.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { SaveBarComponent } from '../../../shared/ui/save-bar/save-bar.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { fadeInUp } from '../../../shared/animations';

/**
 * One scoring config per insurer, not per branch, hence outside the branch master-detail.
 * The UI shows percentages (0..100); the backend contract uses fractions (0..1).
 */
@Component({
  selector: 'app-scoring-config',
  imports: [
    BadgeComponent,
    SwitchComponent,
    CardComponent,
    InputComponent,
    InlineLoadingComponent,
    SaveBarComponent,
  ],
  animations: [fadeInUp],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './scoring-config.component.html',
  styleUrl: './scoring-config.component.scss',
})
export class ScoringConfigComponent {
  private readonly scoringService = inject(ScoringRulesService);

  protected readonly riskFactors = RISK_FACTORS;
  protected readonly riskBands = RISK_BANDS;
  protected readonly bandLabel = riskBandLabel;

  protected readonly draft = signal<ScoringConfig>(this.skeleton());

  // The component is recreated on every visit (`@if` in reglas.component.html), so `load()` reruns;
  // without this flag the empty skeleton would show until the response arrives.
  protected readonly loading = signal(true);

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
  }

  /**
   * Weights are relative: the engine computes `Σ(score × weight) / Σ(weight)`, so they needn't add up
   * to 100%. The UI shows the effective weight, which is what the engine actually applies.
   */
  private readonly totalWeight = computed(() =>
    this.draft().factors.reduce((sum, f) => sum + f.weight, 0),
  );

  protected effectiveWeightPct(factorId: string): string {
    const total = this.totalWeight();
    const factor = this.draft().factors.find((f) => f.factorId === factorId);
    if (!factor || total === 0) {
      return '—';
    }
    return `${Math.round((factor.weight / total) * 1000) / 10}%`;
  }

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
      hint: 'corren siempre',
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
      hint: 'análisis pesado',
      ids: ['document_inconsistency', 'image_reuse', 'image_web_match'],
      heavy: true,
    },
  ];

  protected factorsOf(group: { ids: string[] }): { id: string; label: string }[] {
    return group.ids
      .map((id) => this.riskFactors.find((f) => f.id === id))
      .filter((f): f is { id: string; label: string } => f != null);
  }

  protected effectiveWeightRatio(factorId: string): number {
    const total = this.totalWeight();
    const factor = this.draft().factors.find((f) => f.factorId === factorId);
    if (!factor || total === 0) {
      return 0;
    }
    return (factor.weight / total) * 100;
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

  /**
   * No factors and the 4 default bands. `enabled` is always true and not exposed: the backend flag
   * gates nothing, the engine scores whenever there are factors.
   */
  private skeleton(): ScoringConfig {
    return {
      enabled: true,
      fullAnalysisOnFastTrack: false,
      factors: [],
      bands: RISK_BANDS.map((band, i) => ({ band, minScoreInclusive: [0, 0.3, 0.6, 0.8][i] })),
    };
  }

  /**
   * An unconfigured insurer gets an empty config (no bands), filled with the defaults. Best-effort:
   * if the backend is down, the skeleton stays. `showLoading` only on first load: the post-save
   * reload already has the button's indicator and would otherwise flicker.
   */
  private load(showLoading = false): void {
    if (showLoading) {
      this.loading.set(true);
    }
    this.scoringService
      .get()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (dto) => {
          const bands = dto.bands.length ? dto.bands : this.skeleton().bands;
          const loaded: ScoringConfig = {
            enabled: true,
            fullAnalysisOnFastTrack: dto.fullAnalysisOnFastTrack ?? false,
            factors: dto.factors,
            bands,
          };
          this.draft.set(loaded);
          this.persisted.set(structuredClone(loaded));
        },
        error: () => {
          /* backend down: keep the skeleton */
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
    return f ? this.pctFromRatio(f.weight) : '';
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
    if (this.saving()) {
      return;
    }
    this.error.set(null);
    const sc = this.draft();
    const dto: ScoringConfigDto = {
      enabled: true,
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
