import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Observable, forkJoin, of } from 'rxjs';

import {
  Coverage,
  DOCUMENT_TYPES,
  FastTrackConfig,
  RamoRules,
} from '../../../core/models/business-rules';
import { BranchOption, BranchesService } from '../branches.service';
import { FastTrackConfigDto, FastTrackRulesService } from '../fast-track-rules.service';
import { CoverageDetail, CoverageUpsertRequest, CoveragesRulesService } from '../coverages-rules.service';
import { ClaimCauseOption, CoverageExclusionsService } from '../coverage-exclusions.service';
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
 * (cases-service para Coberturas, rules-service para las otras). La lista y el ABM de ramos salen del
 * catálogo REAL (BranchesService → rules-service /branches): alta, renombre y baja pegan al backend.
 * Ojo: branch es un catálogo GLOBAL (compartido por todas las aseguradoras), no una config por
 * aseguradora — por eso el ABM administra el catálogo maestro.
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
  private readonly branchesService = inject(BranchesService);
  private readonly ftService = inject(FastTrackRulesService);
  private readonly coveragesService = inject(CoveragesRulesService);
  private readonly exclusionsService = inject(CoverageExclusionsService);
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
  // Hechos generadores del ramo seleccionado (id + nombre), para el selector de exclusiones duras
  // por cobertura. Se cargan del backend al elegir el ramo.
  protected readonly claimCauses = signal<ClaimCauseOption[]>([]);

  // ABM de ramos (catálogo global branch, vía BranchesService).
  protected readonly newRamoName = signal('');
  protected readonly creatingRamo = signal(false);
  protected readonly createRamoError = signal<string | null>(null);

  protected readonly renaming = signal(false);
  protected readonly renameSaved = signal(false);
  protected readonly renameError = signal<string | null>(null);

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

  constructor() {
    // La lista sale del catálogo real de ramos (tabla branch). Cada ramo arranca como un shell
    // (solo id + nombre); el detalle de cada solapa se carga del backend al seleccionarlo.
    this.branchesService.list().subscribe((branches) => {
      const ramos = branches.map((b) => this.shellFromBranch(b));
      this.ramos.set(ramos);
      if (ramos.length > 0) {
        this.select(ramos[0]);
      }
    });
  }

  /** Shell de RamoRules a partir del ramo real: id + nombre; el resto lo llena el backend al select. */
  private shellFromBranch(branch: BranchOption): RamoRules {
    return {
      id: String(branch.id),
      name: branch.name,
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
    this.ftSaved.set(false);
    this.ftError.set(null);
    this.covSaved.set(false);
    this.covError.set(null);
    this.docSaved.set(false);
    this.docError.set(null);
    this.rulesSaved.set(false);
    this.rulesError.set(null);
    this.renameSaved.set(false);
    this.renameError.set(null);
    this.loadClaimCauses(r);
    this.loadFastTrackFromBackend(r);
    this.loadCoveragesFromBackend(r);
    this.loadDocumentsFromBackend(r);
    this.loadBusinessRulesFromBackend(r);
  }

  /** Catálogo de hechos generadores del ramo, para el selector de exclusiones duras por cobertura. */
  private loadClaimCauses(r: RamoRules): void {
    this.claimCauses.set([]);
    const branchId = this.branchIdOf(r);
    if (branchId == null) {
      return;
    }
    this.exclusionsService.listClaimCauses(branchId).subscribe({
      next: (options) => this.claimCauses.set(options),
      error: () => {
        /* backend caído: el selector queda vacío, sin romper la pantalla */
      },
    });
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
      next: (list) => {
        this.overlayCoverages(list);
        this.loadCoverageExclusions(list);
      },
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
      // Las exclusiones duras (por hecho generador) viven en rules-service, no en este detalle:
      // arrancan vacías y las completa loadCoverageExclusions.
      excludedClaimCauseIds: [],
    }));
    this.loadedCoverages = coverages;
    this.draft.update((d) => (d ? { ...d, coverages } : d));
  }

  /**
   * Trae, por cada cobertura persistida, los hechos generadores que excluye (rules-service) y los
   * mergea en el draft. Best-effort e independiente por cobertura: si una falla, las otras igual
   * cargan. Solo para coberturas con id real; las altas locales (`cov-…`) todavía no existen en el
   * backend.
   */
  private loadCoverageExclusions(list: CoverageDetail[]): void {
    list.forEach((c) => {
      this.exclusionsService.get(c.id).subscribe({
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
    // Reflejarlo también en el baseline cargado, para que el diff de guardado no lo marque sucio.
    this.loadedCoverages = this.loadedCoverages.map((c) =>
      c.id === coverageId ? { ...c, excludedClaimCauseIds: ids } : c,
    );
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

  // ───────────────── Ramos: ABM (catálogo global branch) ─────────────────
  protected setNewRamoName(name: string): void {
    this.newRamoName.set(name);
    this.createRamoError.set(null);
  }

  protected createRamo(): void {
    const name = this.newRamoName().trim();
    if (name === '' || this.creatingRamo()) {
      return;
    }
    this.createRamoError.set(null);
    this.creatingRamo.set(true);
    this.branchesService.create(name).subscribe({
      next: (branch) => {
        this.creatingRamo.set(false);
        this.newRamoName.set('');
        const ramo = this.shellFromBranch(branch);
        this.ramos.update((list) => [...list, ramo]);
        this.select(ramo);
      },
      error: (e: unknown) => {
        this.creatingRamo.set(false);
        this.createRamoError.set(this.backendErrorMessage(e));
      },
    });
  }

  /** Edita el nombre del ramo en el draft (local); se persiste con saveName(). */
  protected setName(name: string): void {
    this.draft.update((d) => (d ? { ...d, name } : d));
    this.renameSaved.set(false);
  }

  protected saveName(): void {
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
        // Refleja el nombre nuevo en la lista de la izquierda.
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

  protected readonly ramoToDelete = signal<RamoRules | null>(null);
  protected readonly deleting = signal(false);
  protected readonly deleteError = signal<string | null>(null);

  protected requestDeleteRamo(): void {
    const d = this.draft();
    if (d) {
      this.deleteError.set(null);
      this.ramoToDelete.set(d);
    }
  }

  protected cancelDeleteRamo(): void {
    this.ramoToDelete.set(null);
  }

  protected confirmDeleteRamo(): void {
    const ramo = this.ramoToDelete();
    const branchId = ramo ? this.branchIdOf(ramo) : null;
    if (!ramo || branchId == null || this.deleting()) {
      return;
    }
    this.deleteError.set(null);
    this.deleting.set(true);
    this.branchesService.remove(branchId).subscribe({
      next: () => {
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
      },
      error: (e: unknown) => {
        this.deleting.set(false);
        this.deleteError.set(this.backendErrorMessage(e));
      },
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
      excludedClaimCauseIds: [],
    };
    this.draft.update((d) => (d ? { ...d, coverages: [...d.coverages, coverage] } : d));
    this.markDirty();
  }

  // ───────────────── Exclusiones duras por cobertura (hecho generador) ─────────────────
  /** Una cobertura ya persistida (id numérico) puede configurar exclusiones; una local todavía no. */
  protected canEditExclusions(c: Coverage): boolean {
    return this.isPersistedId(c.id);
  }

  protected isCauseExcluded(c: Coverage, causeId: number): boolean {
    return (c.excludedClaimCauseIds ?? []).includes(causeId);
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
      // Exclusiones duras (por hecho generador) solo de las coberturas ya persistidas: una recién
      // creada no tiene id real todavía y sus exclusiones se configuran tras el reload.
      ...toUpdate.map((c) =>
        this.exclusionsService.save(branchId, Number(c.id), c.excludedClaimCauseIds ?? []),
      ),
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

  // ───────────────── Helpers ─────────────────
  private patch(partial: Partial<RamoRules>): void {
    this.draft.update((d) => (d ? { ...d, ...partial } : d));
    this.markDirty();
  }

  private patchFastTrack(fn: (ft: FastTrackConfig) => FastTrackConfig): void {
    this.draft.update((d) => (d ? { ...d, fastTrack: fn(d.fastTrack) } : d));
    this.markDirty();
  }

  // Cada solapa persiste con su propio botón "Guardar X"; ya no hay un guardado global de ramo que
  // dependa de un flag "sucio", así que marcar cambios quedó sin efecto (se conserva el gancho por
  // si alguna solapa quiere resaltar cambios sin guardar en el futuro).
  private markDirty(): void {
    /* no-op */
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
