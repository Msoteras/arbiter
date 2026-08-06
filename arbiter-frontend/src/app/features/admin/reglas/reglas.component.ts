import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import {
  Coverage,
  DOCUMENT_TYPES,
  FastTrackConfig,
  RamoRules,
  RISK_FACTORS,
  ScoringConfig,
} from '../../../core/models/business-rules';
import { RISK_BANDS, RiskBand, riskBandLabel } from '../../../core/models/risk-band';
import { RulesConfigService } from '../rules-config.service';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { ModalComponent } from '../../../shared/ui/modal/modal.component';
import { StringListEditorComponent } from './string-list-editor.component';

type TabId = 'coberturas' | 'fastTrack' | 'documentacion' | 'scoring' | 'reglas';

/**
 * Configuración de reglas del referente, Ramo-céntrica. Master (lista de ramos) + detalle con
 * solapas: Coberturas, Fast Track, Documentación, Scoring y Reglas. El Fast Track y el scoring
 * son por ramo. Trabaja sobre un draft en memoria; "Guardar" persiste vía RulesConfigService
 * (mock hasta que exista rules-service). Porcentajes en la UI (0..100) ↔ fracción (0..1) en el
 * modelo, que es el contrato del back.
 */
@Component({
  selector: 'app-reglas',
  imports: [
    BadgeComponent,
    ButtonComponent,
    CardComponent,
    EmptyStateComponent,
    InputComponent,
    ModalComponent,
    StringListEditorComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './reglas.component.html',
  styleUrl: './reglas.component.scss',
})
export class ReglasComponent {
  private readonly service = inject(RulesConfigService);

  protected readonly docTypes = DOCUMENT_TYPES;
  protected readonly riskFactors = RISK_FACTORS;
  protected readonly riskBands = RISK_BANDS;
  protected readonly bandLabel = riskBandLabel;

  protected readonly tabs: { id: TabId; label: string }[] = [
    { id: 'coberturas', label: 'Coberturas' },
    { id: 'fastTrack', label: 'Fast Track' },
    { id: 'documentacion', label: 'Documentación' },
    { id: 'scoring', label: 'Scoring de fraude' },
    { id: 'reglas', label: 'Reglas de negocio' },
  ];
  protected readonly activeTab = signal<TabId>('coberturas');

  protected readonly ramos = signal<RamoRules[]>([]);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly draft = signal<RamoRules | null>(null);
  protected readonly dirty = signal(false);
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);

  protected readonly weightSumPct = computed(() => {
    const sc = this.draft()?.scoring;
    if (!sc) {
      return 0;
    }
    return Math.round(sc.factors.reduce((sum, f) => sum + f.weight, 0) * 1000) / 10;
  });
  protected readonly weightsBalanced = computed(() => Math.abs(this.weightSumPct() - 100) < 0.05);

  constructor() {
    this.service.list().subscribe((list) => {
      this.ramos.set(list);
      if (list.length > 0) {
        this.select(list[0]);
      }
    });
  }

  protected isSelected(r: RamoRules): boolean {
    return this.selectedId() === r.id;
  }

  protected select(r: RamoRules): void {
    this.selectedId.set(r.id);
    this.draft.set(structuredClone(r));
    this.activeTab.set('coberturas');
    this.dirty.set(false);
    this.saved.set(false);
  }

  protected setTab(t: TabId): void {
    this.activeTab.set(t);
  }

  // ───────────────── Alta / renombre / baja de ramos ─────────────────
  // Mockeado: hoy Arbiter solo soporta Celular y Tecnología, pero el alta/baja queda armada
  // para cuando cada aseguradora administre su propio catálogo de productos.
  protected setName(name: string): void {
    this.patch({ name });
  }

  protected addRamo(): void {
    const ramo: RamoRules = {
      id: `ramo-${Date.now()}`,
      name: 'Nuevo ramo',
      coverages: [],
      commonExclusions: [],
      requiredDocuments: [],
      businessRules: [],
      franchiseRatio: null,
      reportingWindowDays: null,
      maxAnnualClaims: null,
      fastTrack: {
        enabled: false,
        minPolicyAgeMonths: null,
        maxPriorClaims: null,
        priorClaimsWindowMonths: null,
        maxClaimedAmountRatio: null,
        requiresUpToDatePolicy: true,
        requiredDocumentTypes: [],
        criteria: [],
      },
      scoring: {
        enabled: false,
        factors: [],
        bands: RISK_BANDS.map((band, i) => ({ band, minScoreInclusive: [0, 0.3, 0.6, 0.8][i] })),
      },
    };
    this.service.create(ramo).subscribe((created) => {
      this.ramos.update((list) => [...list, created]);
      this.select(created);
    });
  }

  protected readonly ramoToDelete = signal<RamoRules | null>(null);
  protected readonly deleting = signal(false);

  protected requestDeleteRamo(): void {
    const d = this.draft();
    if (d) {
      this.ramoToDelete.set(d);
    }
  }

  protected cancelDeleteRamo(): void {
    this.ramoToDelete.set(null);
  }

  protected confirmDeleteRamo(): void {
    const ramo = this.ramoToDelete();
    if (!ramo || this.deleting()) {
      return;
    }
    this.deleting.set(true);
    this.service.remove(ramo.id).subscribe(() => {
      this.ramos.update((list) => list.filter((r) => r.id !== ramo.id));
      this.deleting.set(false);
      this.ramoToDelete.set(null);
      const next = this.ramos()[0];
      if (next) {
        this.select(next);
      } else {
        this.selectedId.set(null);
        this.draft.set(null);
      }
    });
  }

  // ───────────────── Coberturas ─────────────────
  protected addCoverage(): void {
    const coverage: Coverage = {
      id: `cov-${Date.now()}`,
      name: '',
      clause: '',
      insuredAmount: null,
      deductibleRatio: null,
      exclusions: [],
    };
    this.draft.update((d) => (d ? { ...d, coverages: [...d.coverages, coverage] } : d));
    this.markDirty();
  }

  protected removeCoverage(id: string): void {
    this.draft.update((d) => (d ? { ...d, coverages: d.coverages.filter((c) => c.id !== id) } : d));
    this.markDirty();
  }

  protected setCoverageField(id: string, patch: Partial<Coverage>): void {
    this.draft.update((d) =>
      d ? { ...d, coverages: d.coverages.map((c) => (c.id === id ? { ...c, ...patch } : c)) } : d,
    );
    this.markDirty();
  }

  protected setCoverageAmount(id: string, value: string): void {
    this.setCoverageField(id, { insuredAmount: this.intFromStr(value) });
  }

  protected setCoverageDeductible(id: string, value: string): void {
    this.setCoverageField(id, { deductibleRatio: this.ratioFromPct(value) });
  }

  protected coverageDeductiblePct(c: Coverage): string {
    return this.pctFromRatio(c.deductibleRatio);
  }

  protected coverageAmountStr(c: Coverage): string {
    return c.insuredAmount == null ? '' : String(c.insuredAmount);
  }

  protected setCommonExclusions(items: string[]): void {
    this.patch({ commonExclusions: items });
  }

  // ───────────────── Documentación ─────────────────
  protected isDocRequired(code: string): boolean {
    return this.draft()?.requiredDocuments.includes(code) ?? false;
  }

  protected toggleDoc(code: string): void {
    this.draft.update((d) => {
      if (!d) {
        return d;
      }
      const has = d.requiredDocuments.includes(code);
      return {
        ...d,
        requiredDocuments: has
          ? d.requiredDocuments.filter((c) => c !== code)
          : [...d.requiredDocuments, code],
      };
    });
    this.markDirty();
  }

  // ───────────────── Fast Track ─────────────────
  protected toggleFastTrack(): void {
    this.patchFastTrack((ft) => ({ ...ft, enabled: !ft.enabled }));
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

  // ───────────────── Scoring ─────────────────
  protected toggleScoring(): void {
    this.patchScoring((sc) => ({ ...sc, enabled: !sc.enabled }));
  }

  protected isFactorActive(id: string): boolean {
    return this.draft()?.scoring.factors.some((f) => f.factorId === id) ?? false;
  }

  protected toggleFactor(id: string): void {
    this.patchScoring((sc) => {
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
    const f = this.draft()?.scoring.factors.find((x) => x.factorId === id);
    return f ? this.pctFromRatio(f.weight) : '';
  }

  protected setFactorWeight(id: string, value: string): void {
    const ratio = this.ratioFromPct(value) ?? 0;
    this.patchScoring((sc) => ({
      ...sc,
      factors: sc.factors.map((f) => (f.factorId === id ? { ...f, weight: ratio } : f)),
    }));
  }

  protected bandCutPct(band: RiskBand): string {
    const b = this.draft()?.scoring.bands.find((x) => x.band === band);
    return b ? this.pctFromRatio(b.minScoreInclusive) : '';
  }

  protected setBandCut(band: RiskBand, value: string): void {
    const ratio = this.ratioFromPct(value) ?? 0;
    this.patchScoring((sc) => ({
      ...sc,
      bands: sc.bands.map((b) => (b.band === band ? { ...b, minScoreInclusive: ratio } : b)),
    }));
  }

  // ───────────────── Reglas de negocio ─────────────────
  protected setBusinessRules(items: string[]): void {
    this.patch({ businessRules: items });
  }

  protected setFranchisePct(v: string): void {
    this.patch({ franchiseRatio: this.ratioFromPct(v) });
  }

  protected setReportingWindow(v: string): void {
    this.patch({ reportingWindowDays: this.intFromStr(v) });
  }

  protected setMaxAnnualClaims(v: string): void {
    this.patch({ maxAnnualClaims: this.intFromStr(v) });
  }

  protected franchisePctStr(): string {
    return this.pctFromRatio(this.draft()?.franchiseRatio ?? null);
  }
  protected reportingWindowStr(): string {
    return this.intStr(this.draft()?.reportingWindowDays);
  }
  protected maxAnnualClaimsStr(): string {
    return this.intStr(this.draft()?.maxAnnualClaims);
  }

  // ───────────────── Guardar / descartar ─────────────────
  protected save(): void {
    const d = this.draft();
    if (!d || !this.dirty() || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.service.save(d).subscribe((updated) => {
      this.ramos.update((list) => list.map((r) => (r.id === updated.id ? updated : r)));
      this.saving.set(false);
      this.dirty.set(false);
      this.saved.set(true);
    });
  }

  protected discard(): void {
    const original = this.ramos().find((r) => r.id === this.selectedId());
    if (original) {
      this.draft.set(structuredClone(original));
      this.dirty.set(false);
      this.saved.set(false);
    }
  }

  // ───────────────── Helpers ─────────────────
  private patch(partial: Partial<RamoRules>): void {
    this.draft.update((d) => (d ? { ...d, ...partial } : d));
    this.markDirty();
  }

  private patchFastTrack(fn: (ft: FastTrackConfig) => FastTrackConfig): void {
    this.draft.update((d) => (d ? { ...d, fastTrack: fn(d.fastTrack) } : d));
    this.markDirty();
  }

  private patchScoring(fn: (sc: ScoringConfig) => ScoringConfig): void {
    this.draft.update((d) => (d ? { ...d, scoring: fn(d.scoring) } : d));
    this.markDirty();
  }

  private markDirty(): void {
    this.dirty.set(true);
    this.saved.set(false);
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
