import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { CheckboxComponent } from '../../../shared/ui/checkbox/checkbox.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { CatalogOption, FastTrackConfig, RulesService } from './reglas.service';

/**
 * Backoffice del referente para cargar los umbrales Fast Track por (ramo, cobertura). Cascada
 * Ramo → Cobertura → form de umbrales; guardar hace un PUT (upsert + snapshot de historial en el
 * backend). Los cambios se aplican sin redeploy. Eje (ramo, cobertura), fiel al DER.
 */
@Component({
  selector: 'app-reglas',
  imports: [ButtonComponent, CardComponent, CheckboxComponent, InputComponent, SelectComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './reglas.component.html',
  styleUrl: './reglas.component.scss',
})
export class ReglasComponent {
  private readonly service = inject(RulesService);

  protected readonly branchOptions = signal<SelectOption[]>([]);
  protected readonly coverageOptions = signal<SelectOption[]>([]);
  protected readonly selectedBranch = signal('');
  protected readonly selectedCoverage = signal('');

  // Los inputs devuelven strings; se parsean al construir el config.
  protected readonly ratio = signal('');
  protected readonly maxPriorClaims = signal('');
  protected readonly requiresUpToDate = signal(false);
  protected readonly requiredDocs = signal('');

  protected readonly loadingConfig = signal(false);
  protected readonly saving = signal(false);
  protected readonly savedOk = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly canEdit = computed(() => this.selectedCoverage() !== '');

  constructor() {
    this.service.branches().subscribe({
      next: (list) => this.branchOptions.set(list.map(toOption)),
      error: () => this.error.set('No se pudieron cargar los ramos.'),
    });
  }

  protected onBranchChange(value: string): void {
    this.selectedBranch.set(value);
    this.selectedCoverage.set('');
    this.coverageOptions.set([]);
    this.resetForm();
    if (!value) {
      return;
    }
    this.service.coverages(Number(value)).subscribe({
      next: (list) => this.coverageOptions.set(list.map(toOption)),
      error: () => this.error.set('No se pudieron cargar las coberturas.'),
    });
  }

  protected onCoverageChange(value: string): void {
    this.selectedCoverage.set(value);
    this.resetForm();
    if (!value) {
      return;
    }
    this.loadingConfig.set(true);
    this.service.getFastTrack(Number(this.selectedBranch()), Number(value)).subscribe({
      next: (config) => {
        this.applyConfig(config);
        this.loadingConfig.set(false);
      },
      error: () => {
        this.error.set('No se pudo cargar la configuración.');
        this.loadingConfig.set(false);
      },
    });
  }

  protected save(): void {
    this.error.set(null);
    this.savedOk.set(false);
    this.saving.set(true);
    this.service
      .saveFastTrack(Number(this.selectedBranch()), Number(this.selectedCoverage()), this.buildConfig())
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.savedOk.set(true);
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.error.set(err.error?.detail ?? 'No se pudo guardar. Probá de nuevo.');
        },
      });
  }

  private applyConfig(config: FastTrackConfig): void {
    this.ratio.set(config.maxClaimedAmountRatio != null ? String(config.maxClaimedAmountRatio) : '');
    this.maxPriorClaims.set(config.maxPriorClaims != null ? String(config.maxPriorClaims) : '');
    this.requiresUpToDate.set(config.requiresUpToDatePolicy === true);
    this.requiredDocs.set((config.requiredDocumentTypes ?? []).join(', '));
  }

  private buildConfig(): FastTrackConfig {
    const docs = this.requiredDocs()
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    return {
      maxClaimedAmountRatio: this.ratio().trim() === '' ? null : Number(this.ratio()),
      maxPriorClaims: this.maxPriorClaims().trim() === '' ? null : Number(this.maxPriorClaims()),
      requiresUpToDatePolicy: this.requiresUpToDate(),
      requiredDocumentTypes: docs.length > 0 ? docs : null,
    };
  }

  private resetForm(): void {
    this.ratio.set('');
    this.maxPriorClaims.set('');
    this.requiresUpToDate.set(false);
    this.requiredDocs.set('');
    this.savedOk.set(false);
    this.error.set(null);
  }
}

function toOption(option: CatalogOption): SelectOption {
  return { value: String(option.id), label: option.name };
}
