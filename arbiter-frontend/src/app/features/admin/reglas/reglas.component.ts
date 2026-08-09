import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Observable, forkJoin, of } from 'rxjs';

import {
  Coverage,
  DOCUMENT_TYPES,
  FastTrackConfig,
  RamoRules,
} from '../../../core/models/business-rules';
import { RulesConfigService } from '../rules-config.service';
import { FastTrackConfigDto, FastTrackRulesService } from '../fast-track-rules.service';
import { CoverageDetail, CoverageUpsertRequest, CoveragesRulesService } from '../coverages-rules.service';
import { DocumentRulesService } from '../document-rules.service';
import { BusinessRulesTextService } from '../business-rules-text.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { ModalComponent } from '../../../shared/ui/modal/modal.component';
import { ScoringConfigComponent } from '../scoring-config/scoring-config.component';
import { StringListEditorComponent } from './string-list-editor.component';

type TabId = 'coberturas' | 'fastTrack' | 'documentacion' | 'reglas';

/**
 * Configuración de reglas del referente, Ramo-céntrica. Master (lista de ramos) + detalle con
 * solapas: Coberturas, Fast Track, Documentación y Reglas. Trabaja sobre un draft en memoria por
 * solapa; cada una tiene su propio botón "Guardar X" que persiste contra el backend real
 * (cases-service para Coberturas, rules-service para las otras). El "Guardar cambios" /
 * "Descartar" globales de arriba solo cubren el nombre del ramo y el alta/baja de ramos, que
 * siguen en RulesConfigService (mock): no existe todavía un CRUD de Branch en el backend.
 * El scoring de fraude NO es por ramo: es una config única por aseguradora y vive en una sección
 * aparte de la pantalla (ScoringConfigComponent), fuera de este master-detail.
 * Porcentajes en la UI (0..100) ↔ fracción (0..1) en el modelo, que es el contrato del back.
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
    StringListEditorComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './reglas.component.html',
  styleUrl: './reglas.component.scss',
})
export class ReglasComponent {
  private readonly service = inject(RulesConfigService);
  private readonly ftService = inject(FastTrackRulesService);
  private readonly coveragesService = inject(CoveragesRulesService);
  private readonly documentsService = inject(DocumentRulesService);
  private readonly rulesTextService = inject(BusinessRulesTextService);

  // Estado de guardado real por solapa (Fast Track, Coberturas, Documentación y Reglas de negocio
  // persisten contra el backend, cada una con su propio botón — el "Guardar cambios" global sigue
  // existiendo solo para el nombre del ramo, que todavía es mock (alta/baja de ramo también).
  protected readonly ftSaving = signal(false);
  protected readonly ftSaved = signal(false);
  protected readonly ftError = signal<string | null>(null);

  protected readonly covSaving = signal(false);
  protected readonly covSaved = signal(false);
  protected readonly covError = signal<string | null>(null);
  private loadedCoverages: Coverage[] = [];

  protected readonly docSaving = signal(false);
  protected readonly docSaved = signal(false);
  protected readonly docError = signal<string | null>(null);

  protected readonly rulesSaving = signal(false);
  protected readonly rulesSaved = signal(false);
  protected readonly rulesError = signal<string | null>(null);

  protected readonly docTypes = DOCUMENT_TYPES;

  protected readonly tabs: { id: TabId; label: string }[] = [
    { id: 'coberturas', label: 'Coberturas' },
    { id: 'fastTrack', label: 'Fast Track' },
    { id: 'documentacion', label: 'Documentación' },
    { id: 'reglas', label: 'Reglas de negocio' },
  ];
  protected readonly activeTab = signal<TabId>('coberturas');

  protected readonly ramos = signal<RamoRules[]>([]);
  protected readonly selectedId = signal<string | null>(null);
  // Qué muestra el panel derecho: el detalle del ramo seleccionado ('ramo') o el scoring de la
  // aseguradora ('scoring'), que no pertenece a ningún ramo y se elige desde su propio recuadro.
  protected readonly view = signal<'ramo' | 'scoring'>('ramo');
  protected readonly draft = signal<RamoRules | null>(null);
  protected readonly dirty = signal(false);
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);

  constructor() {
    this.service.list().subscribe((list) => {
      this.ramos.set(list);
      if (list.length > 0) {
        this.select(list[0]);
      }
    });
  }

  protected isSelected(r: RamoRules): boolean {
    return this.view() === 'ramo' && this.selectedId() === r.id;
  }

  /** El recuadro "Scoring de riesgo" de la izquierda: muestra el scoring de la aseguradora a la derecha. */
  protected selectScoring(): void {
    this.view.set('scoring');
  }

  protected select(r: RamoRules): void {
    this.view.set('ramo');
    this.selectedId.set(r.id);
    this.draft.set(structuredClone(r));
    this.activeTab.set('coberturas');
    this.dirty.set(false);
    this.saved.set(false);
    this.ftSaved.set(false);
    this.ftError.set(null);
    this.covSaved.set(false);
    this.covError.set(null);
    this.docSaved.set(false);
    this.docError.set(null);
    this.rulesSaved.set(false);
    this.rulesError.set(null);
    this.loadFastTrackFromBackend(r);
    this.loadCoveragesFromBackend(r);
    this.loadDocumentsFromBackend(r);
    this.loadBusinessRulesFromBackend(r);
  }

  /**
   * Trae del backend el Fast Track persistido y lo superpone sobre el draft, para que el referente
   * vea lo que está guardado (no el semilla del mock). Best-effort: si falla o no hay config, deja
   * los valores del mock. Solo aplica a ramos con branchId real (id numérico).
   */
  private loadFastTrackFromBackend(r: RamoRules): void {
    const branchId = this.branchIdOf(r);
    if (branchId == null) {
      return;
    }
    this.ftService.loadForBranch(branchId).subscribe({
      next: (dto) => this.overlayFastTrack(dto),
      error: () => {
        /* backend caído: nos quedamos con el mock, sin romper la pantalla */
      },
    });
  }

  private overlayFastTrack(dto: FastTrackConfigDto | null): void {
    // Config vacía en la DB = Fast Track no configurado para este ramo.
    if (!dto || this.isEmptyFastTrack(dto)) {
      this.draft.update((d) => (d ? { ...d, fastTrack: { ...d.fastTrack, enabled: false } } : d));
      return;
    }
    this.draft.update((d) =>
      d
        ? {
            ...d,
            fastTrack: {
              ...d.fastTrack,
              enabled: true,
              maxClaimedAmountRatio: dto.maxClaimedAmountRatio,
              maxPriorClaims: dto.maxPriorClaims,
              requiresUpToDatePolicy: dto.requiresUpToDatePolicy ?? d.fastTrack.requiresUpToDatePolicy,
              requiredDocumentTypes: dto.requiredDocumentTypes ?? [],
            },
          }
        : d,
    );
  }

  private isEmptyFastTrack(dto: FastTrackConfigDto): boolean {
    return (
      dto.maxClaimedAmountRatio == null &&
      dto.maxPriorClaims == null &&
      dto.requiresUpToDatePolicy == null &&
      (dto.requiredDocumentTypes == null || dto.requiredDocumentTypes.length === 0)
    );
  }

  /**
   * Trae del backend las coberturas reales del ramo (cases-service) y las exclusiones comunes
   * (rules-service), reemplazando lo que trajera el mock. Guarda una copia (`loadedCoverages`)
   * para poder diffear altas/bajas/ediciones al guardar.
   */
  private loadCoveragesFromBackend(r: RamoRules): void {
    const branchId = this.branchIdOf(r);
    if (branchId == null) {
      return;
    }
    this.coveragesService.listDetailed(branchId).subscribe({
      next: (list) => this.overlayCoverages(list),
      error: () => {
        /* backend caído: nos quedamos con el mock, sin romper la pantalla */
      },
    });
    this.rulesTextService.getExclusions(branchId).subscribe({
      next: (items) => {
        this.draft.update((d) => (d ? { ...d, commonExclusions: items } : d));
      },
      error: () => {
        /* best-effort */
      },
    });
  }

  private overlayCoverages(list: CoverageDetail[]): void {
    const coverages: Coverage[] = list.map((c) => ({
      id: String(c.id),
      name: c.name,
      clause: c.clause ?? '',
      insuredAmount: null,
      deductibleRatio: c.deductibleRatio,
      reportingWindowDays: c.reportingWindowDays,
      maxAnnualClaims: c.maxAnnualClaims,
      exclusions: c.exclusions ?? [],
    }));
    this.loadedCoverages = coverages;
    this.draft.update((d) => (d ? { ...d, coverages } : d));
  }

  /** Trae la agenda documental real del ramo (rules-service, fan-out invisible en el backend). */
  private loadDocumentsFromBackend(r: RamoRules): void {
    const branchId = this.branchIdOf(r);
    if (branchId == null) {
      return;
    }
    this.documentsService.get(branchId).subscribe({
      next: (types) => {
        this.draft.update((d) => (d ? { ...d, requiredDocuments: types } : d));
      },
      error: () => {
        /* backend caído: nos quedamos con el mock, sin romper la pantalla */
      },
    });
  }

  /** Trae las reglas de negocio en texto libre del ramo (rules-service). */
  private loadBusinessRulesFromBackend(r: RamoRules): void {
    const branchId = this.branchIdOf(r);
    if (branchId == null) {
      return;
    }
    this.rulesTextService.getBusinessRules(branchId).subscribe({
      next: (items) => {
        this.draft.update((d) => (d ? { ...d, businessRules: items } : d));
      },
      error: () => {
        /* backend caído: nos quedamos con el mock, sin romper la pantalla */
      },
    });
  }

  private branchIdOf(r: RamoRules): number | null {
    const n = Number(r.id);
    return Number.isInteger(n) && n > 0 ? n : null;
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
      reportingWindowDays: null,
      maxAnnualClaims: null,
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

  // ───────────────── Reglas de negocio ─────────────────
  protected setBusinessRules(items: string[]): void {
    this.patch({ businessRules: items });
  }

  // ───────────────── Fast Track: persistencia real (rules-service) ─────────────────
  /**
   * Guarda el Fast Track del ramo en el backend (fan-out a las coberturas del ramo). Solo persiste
   * los 4 umbrales que el motor evalúa hoy: monto máx., siniestros previos máx., póliza al día y
   * documentos exigidos. Antigüedad mínima, ventana de siniestros y criterios descriptivos aún no
   * llegan al gate (quedan en el draft/mock). Deshabilitado ⇒ config vacía = sin Fast Track.
   */
  protected saveFastTrack(): void {
    const d = this.draft();
    if (!d || this.ftSaving()) {
      return;
    }
    this.ftError.set(null);
    this.ftSaved.set(false);
    const branchId = this.branchIdOf(d);
    if (branchId == null) {
      this.ftError.set('Este ramo todavía no existe en el backend.');
      return;
    }
    const ft = d.fastTrack;
    const dto: FastTrackConfigDto = ft.enabled
      ? {
          maxClaimedAmountRatio: ft.maxClaimedAmountRatio,
          maxPriorClaims: ft.maxPriorClaims,
          requiresUpToDatePolicy: ft.requiresUpToDatePolicy,
          requiredDocumentTypes: ft.requiredDocumentTypes,
        }
      : { maxClaimedAmountRatio: null, maxPriorClaims: null, requiresUpToDatePolicy: null, requiredDocumentTypes: [] };

    this.ftSaving.set(true);
    this.ftService.saveForBranch(branchId, dto).subscribe({
      next: () => {
        this.ftSaving.set(false);
        this.ftSaved.set(true);
        // Recarga desde el backend para reflejar exactamente lo que quedó persistido.
        this.loadFastTrackFromBackend(d);
      },
      error: (e: unknown) => {
        this.ftSaving.set(false);
        this.ftError.set(this.backendErrorMessage(e));
      },
    });
  }

  // ───────────────── Coberturas: persistencia real (cases-service) ─────────────────
  /**
   * Guarda las coberturas del ramo (alta/edición/baja según cómo cambió el draft frente a
   * `loadedCoverages`) y las exclusiones comunes (rules-service), en un solo botón porque
   * comparten la solapa Coberturas.
   */
  protected saveCoverages(): void {
    const d = this.draft();
    if (!d || this.covSaving()) {
      return;
    }
    this.covError.set(null);
    this.covSaved.set(false);
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
      this.rulesTextService.saveExclusions(branchId, d.commonExclusions),
    ];

    this.covSaving.set(true);
    forkJoin(requests.length ? requests : [of(null)]).subscribe({
      next: () => {
        this.covSaving.set(false);
        this.covSaved.set(true);
        // Recarga: los que eran altas ahora tienen id real del backend.
        this.loadCoveragesFromBackend(d);
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
      exclusions: c.exclusions,
    };
  }

  /** Un id numérico es una cobertura que ya existe en el backend; uno con prefijo `cov-` es un alta local. */
  private isPersistedId(id: string): boolean {
    return /^\d+$/.test(id);
  }

  // ───────────────── Documentación: persistencia real (rules-service) ─────────────────
  protected saveDocuments(): void {
    const d = this.draft();
    if (!d || this.docSaving()) {
      return;
    }
    this.docError.set(null);
    this.docSaved.set(false);
    const branchId = this.branchIdOf(d);
    if (branchId == null) {
      this.docError.set('Este ramo todavía no existe en el backend.');
      return;
    }
    this.docSaving.set(true);
    this.documentsService.save(branchId, d.requiredDocuments).subscribe({
      next: () => {
        this.docSaving.set(false);
        this.docSaved.set(true);
        // Recarga desde el backend para reflejar exactamente lo que quedó persistido.
        this.loadDocumentsFromBackend(d);
      },
      error: (e: unknown) => {
        this.docSaving.set(false);
        this.docError.set(this.backendErrorMessage(e));
      },
    });
  }

  // ───────────────── Reglas de negocio: persistencia real (rules-service) ─────────────────
  protected saveBusinessRules(): void {
    const d = this.draft();
    if (!d || this.rulesSaving()) {
      return;
    }
    this.rulesError.set(null);
    this.rulesSaved.set(false);
    const branchId = this.branchIdOf(d);
    if (branchId == null) {
      this.rulesError.set('Este ramo todavía no existe en el backend.');
      return;
    }
    this.rulesSaving.set(true);
    this.rulesTextService.saveBusinessRules(branchId, d.businessRules).subscribe({
      next: () => {
        this.rulesSaving.set(false);
        this.rulesSaved.set(true);
        // Recarga desde el backend para reflejar exactamente lo que quedó persistido.
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
