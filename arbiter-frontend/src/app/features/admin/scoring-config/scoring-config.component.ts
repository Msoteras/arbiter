import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { RISK_FACTORS, ScoringConfig } from '../../../core/models/business-rules';
import { RISK_BANDS, RiskBand, riskBandLabel } from '../../../core/models/risk-band';
import { ScoringConfigDto, ScoringRulesService } from '../scoring-rules.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { InputComponent } from '../../../shared/ui/input/input.component';

/**
 * Scoring de fraude de la aseguradora. Es UNA sola configuración por aseguradora, no por ramo: el
 * backend la sirve/persiste en `GET|PUT /api/v1/rules/scoring` sin branchId. Por eso vive como
 * sección propia de la pantalla de reglas, fuera del master-detail de ramos — antes estaba metida
 * como una solapa dentro de cada ramo, lo que hacía creer que se configuraba por ramo cuando en
 * realidad todos compartían la misma config.
 *
 * Porcentajes en la UI (0..100) ↔ fracción (0..1) en el modelo, que es el contrato del back.
 */
@Component({
  selector: 'app-scoring-config',
  imports: [ButtonComponent, CardComponent, InputComponent],
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

  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly error = signal<string | null>(null);

  /**
   * Los pesos son **relativos**: el motor divide por el total (`score = Σ(puntaje × peso) / Σ(peso)`),
   * así que el score sale en [0,1] sumen 100%, 190% o 7. Antes la UI advertía que "deberían sumar
   * 100%" — una regla que el motor no tiene y que el seed viola de fábrica (suma 190%). Peor: el
   * número que el referente leía (45%) no era el que se aplicaba (23,7%). Se sacó la advertencia y
   * en su lugar se muestra el **peso efectivo**, que es lo que el motor realmente usa (D17).
   */
  private readonly totalWeight = computed(() =>
    this.draft().factors.reduce((sum, f) => sum + f.weight, 0),
  );

  /** Cuánto pesa de verdad un factor: su peso sobre el total de los activos. */
  protected effectiveWeightPct(factorId: string): string {
    const total = this.totalWeight();
    const factor = this.draft().factors.find((f) => f.factorId === factorId);
    if (!factor || total === 0) {
      return '—';
    }
    return `${Math.round((factor.weight / total) * 1000) / 10}%`;
  }

  constructor() {
    this.load();
  }

  /**
   * Config inicial: sin factores y las 4 bandas del gauge en sus cortes por defecto. El scoring no
   * tiene estado "deshabilitado" en la UI — el motor scorea si hay factores, y el flag `enabled`
   * del backend no gatea nada (por eso `enabled` va siempre en true), así que no lo exponemos.
   */
  private skeleton(): ScoringConfig {
    return {
      enabled: true,
      factors: [],
      bands: RISK_BANDS.map((band, i) => ({ band, minScoreInclusive: [0, 0.3, 0.6, 0.8][i] })),
    };
  }

  /**
   * Trae el scoring real de la aseguradora. Sin config aún ⇒ el backend devuelve una vacía (sin
   * bandas): completamos con las 4 bandas por defecto para que el referente las vea. Best-effort:
   * si el backend está caído, se queda con el skeleton sin romper la pantalla.
   */
  private load(): void {
    this.scoringService.get().subscribe({
      next: (dto) => {
        const bands = dto.bands.length ? dto.bands : this.skeleton().bands;
        this.draft.set({ enabled: true, factors: dto.factors, bands });
      },
      error: () => {
        /* backend caído: nos quedamos con el skeleton */
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

  /** Guarda el scoring de la aseguradora. `enabled` va siempre en true: el motor scorea si hay factores. */
  protected saveScoring(): void {
    if (this.saving()) {
      return;
    }
    this.error.set(null);
    this.saved.set(false);
    const sc = this.draft();
    const dto: ScoringConfigDto = { enabled: true, factors: sc.factors, bands: sc.bands };

    this.saving.set(true);
    this.scoringService.save(dto).subscribe({
      next: () => {
        this.saving.set(false);
        this.saved.set(true);
        // Recarga desde el backend para reflejar exactamente lo que quedó persistido.
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
    this.saved.set(false);
  }

  private backendErrorMessage(e: unknown): string {
    if (e instanceof HttpErrorResponse) {
      if (e.status === 403) {
        return 'No tenés permiso para editar el scoring (se requiere rol Referente).';
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
