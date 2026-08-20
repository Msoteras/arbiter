import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';

import { RISK_FACTORS, ScoringConfig } from '../../../core/models/business-rules';
import { RISK_BANDS, RiskBand, riskBandLabel } from '../../../core/models/risk-band';
import { ScoringConfigDto, ScoringRulesService } from '../scoring-rules.service';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { SaveBarComponent } from '../../../shared/ui/save-bar/save-bar.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';

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
  imports: [
    BadgeComponent,
    CardComponent,
    InputComponent,
    InlineLoadingComponent,
    SaveBarComponent,
  ],
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

  // El componente se destruye y se recrea cada vez que el referente vuelve a este recuadro (ver
  // `@if (view() === 'scoring')` en reglas.component.html), así que `load()` corre de nuevo en
  // cada entrada — sin este flag, esa carga es invisible y el panel se ve armado con el skeleton
  // vacío hasta que llega la respuesta real.
  protected readonly loading = signal(true);

  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Lo último que confirmó el backend: contra esto se decide si quedan cambios sin guardar. */
  private readonly persisted = signal<ScoringConfig | null>(null);

  protected readonly dirty = computed(() => {
    const base = this.persisted();
    return base != null && JSON.stringify(this.draft()) !== JSON.stringify(base);
  });

  /** Vuelve a lo último guardado, sin recargar la pantalla. */
  protected discard(): void {
    const base = this.persisted();
    if (base) {
      this.draft.set(structuredClone(base));
    }
    this.error.set(null);
  }

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

  /**
   * Los factores en dos grupos, porque no cuestan lo mismo. Los de datos salen de lo que el
   * expediente ya tiene y corren siempre; los de documentos e imágenes exigen leer los adjuntos y
   * comparar imágenes, y en Fast Track pueden no correr (ver `fullAnalysisOnFastTrack`). Verlos
   * mezclados escondía que prender uno del segundo grupo tiene un costo que el otro no.
   */
  protected readonly factorGroups: { title: string; hint: string; ids: string[] }[] = [
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
      hint: 'análisis pesado — puede no correr en Fast Track',
      ids: ['document_inconsistency', 'image_reuse', 'image_web_match'],
    },
  ];

  protected factorsOf(group: { ids: string[] }): { id: string; label: string }[] {
    return group.ids
      .map((id) => this.riskFactors.find((f) => f.id === id))
      .filter((f): f is { id: string; label: string } => f != null);
  }

  /**
   * Cuánto pesa el factor sobre el total de los activos, como ancho de barra. Es la misma cuenta
   * que hace el motor (peso / suma de pesos activos), así que la barra muestra el reparto real y
   * no el número crudo, que por sí solo no dice nada — los pesos son relativos entre sí.
   */
  protected effectiveWeightRatio(factorId: string): number {
    const total = this.totalWeight();
    const factor = this.draft().factors.find((f) => f.factorId === factorId);
    if (!factor || total === 0) {
      return 0;
    }
    return (factor.weight / total) * 100;
  }

  /** El tramo de puntaje de cada banda, leído de los cortes configurados. */
  protected bandRangeLabel(band: RiskBand): string {
    const cuts = RISK_BANDS.map((b) => this.bandCutValue(b));
    const index = RISK_BANDS.indexOf(band);
    const from = cuts[index];
    const next = cuts[index + 1];
    // El último tramo llega a 100; los otros terminan justo antes de donde empieza el siguiente.
    return next == null ? `Puntaje ${from}–100` : `Puntaje ${from}–${Math.max(from, next - 1)}`;
  }

  private bandCutValue(band: RiskBand): number {
    if (band === 'LOW') {
      return 0;
    }
    const cut = this.draft().bands.find((b) => b.band === band);
    return cut ? Math.round(cut.minScoreInclusive * 100) : 0;
  }

  /** Ancho de cada tramo en la barra de bandas, para que refleje los cortes configurados. */
  protected bandWidth(band: RiskBand): number {
    const index = RISK_BANDS.indexOf(band);
    const from = this.bandCutValue(band);
    const next = RISK_BANDS[index + 1];
    const to = next ? this.bandCutValue(next) : 100;
    return Math.max(0, to - from);
  }

  /** Mismo semáforo que el fraud-gauge del expediente: el referente ve los colores que verá el analista. */
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
   * Config inicial: sin factores y las 4 bandas del gauge en sus cortes por defecto. El scoring no
   * tiene estado "deshabilitado" en la UI — el motor scorea si hay factores, y el flag `enabled`
   * del backend no gatea nada (por eso `enabled` va siempre en true), así que no lo exponemos.
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
   * Trae el scoring real de la aseguradora. Sin config aún ⇒ el backend devuelve una vacía (sin
   * bandas): completamos con las 4 bandas por defecto para que el referente las vea. Best-effort:
   * si el backend está caído, se queda con el skeleton sin romper la pantalla.
   *
   * `showLoading` solo se prende en la carga inicial: el reload que dispara `saveScoring()` ya
   * tiene su propio indicador (el botón en "Guardando…"), tapar todo el panel de nuevo ahí sería
   * redundante y haría parpadear la pantalla apenas guardaste.
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

  /** Guarda el scoring de la aseguradora. `enabled` va siempre en true: el motor scorea si hay factores. */
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
