import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { BranchOption, BranchesService } from '../branches.service';
import { ExpertFirmAdmin, ExpertFirmRequest, ExpertFirmsService } from '../expert-firms.service';
import {
  PROVIDER_TYPE_OPTIONS,
  ProviderType,
  providerTypeLabel,
} from '../../../core/models/peritaje';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { CheckboxComponent } from '../../../shared/ui/checkbox/checkbox.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';

/** `id` null = new firm. */
interface ExpertFirmDraft extends ExpertFirmRequest {
  id: number | null;
}

/**
 * Insurer-wide expert firms catalog (who a case can be referred to), even though each firm may
 * specialize in a branch. The amount that enables a referral is a rule in rules-service.
 */
@Component({
  selector: 'app-expert-firms-config',
  imports: [
    ButtonComponent,
    CardComponent,
    CheckboxComponent,
    InputComponent,
    SelectComponent,
    EmptyStateComponent,
    BadgeComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './expert-firms-config.component.html',
  styleUrl: './expert-firms-config.component.scss',
})
export class ExpertFirmsConfigComponent {
  private readonly expertFirmsService = inject(ExpertFirmsService);
  private readonly branchesService = inject(BranchesService);

  protected readonly firms = signal<ExpertFirmAdmin[]>([]);
  protected readonly branches = signal<BranchOption[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /** null = no row being edited. */
  protected readonly draft = signal<ExpertFirmDraft | null>(null);
  protected readonly saving = signal(false);

  /** The empty option is a real value (generalist firm), not "unselected". */
  protected readonly branchOptions = computed<SelectOption[]>(() => [
    { value: '', label: 'Todos los ramos' },
    ...this.branches().map((b) => ({ value: String(b.id), label: b.name })),
  ]);

  constructor() {
    this.reload();
    this.branchesService.list().subscribe({ next: (list) => this.branches.set(list) });
  }

  private reload(): void {
    this.loading.set(true);
    this.expertFirmsService.list().subscribe({
      next: (list) => {
        this.firms.set(list);
        this.loading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.loading.set(false);
        this.error.set(err.error?.detail || 'No se pudo cargar el catálogo de peritos');
      },
    });
  }

  protected readonly providerTypeOptions: SelectOption[] = PROVIDER_TYPE_OPTIONS;
  protected readonly providerTypeLabel = providerTypeLabel;

  protected add(): void {
    this.error.set(null);
    this.draft.set({
      id: null,
      name: '',
      email: '',
      zone: '',
      branchId: null,
      active: true,
      providerType: 'ESTUDIO_LIQUIDADOR',
    });
  }

  protected edit(firm: ExpertFirmAdmin): void {
    this.error.set(null);
    this.draft.set({
      id: firm.id,
      name: firm.name,
      email: firm.email,
      zone: firm.zone ?? '',
      branchId: firm.branchId,
      active: firm.active,
      providerType: firm.providerType,
    });
  }

  protected cancel(): void {
    this.draft.set(null);
  }

  protected setField<K extends keyof ExpertFirmDraft>(field: K, value: ExpertFirmDraft[K]): void {
    const current = this.draft();
    if (current) {
      this.draft.set({ ...current, [field]: value });
    }
  }

  protected setProviderType(value: string): void {
    this.setField('providerType', value as ProviderType);
  }

  protected setBranch(value: string): void {
    this.setField('branchId', value ? Number(value) : null);
  }

  /** '' is the generalist firm (every branch). */
  protected branchValue(draft: ExpertFirmDraft): string {
    return draft.branchId == null ? '' : String(draft.branchId);
  }

  protected readonly canSave = computed(() => {
    const d = this.draft();
    // Email is the only channel to the firm: without it a referral goes nowhere.
    return !!d && d.name.trim().length > 0 && d.email.trim().length > 0;
  });

  protected save(): void {
    const d = this.draft();
    if (!d || !this.canSave()) {
      return;
    }
    const request: ExpertFirmRequest = {
      name: d.name.trim(),
      email: d.email.trim(),
      zone: d.zone?.trim() || null,
      branchId: d.branchId,
      active: d.active,
      providerType: d.providerType,
    };
    this.saving.set(true);
    this.error.set(null);
    const call =
      d.id == null
        ? this.expertFirmsService.create(request)
        : this.expertFirmsService.update(d.id, request);
    call.subscribe({
      next: () => {
        this.saving.set(false);
        this.draft.set(null);
        this.reload();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.error.set(err.error?.detail || 'No se pudo guardar el perito');
      },
    });
  }

  /** A 409 (firm has referrals) explains it must be deactivated instead; shown as is. */
  protected remove(firm: ExpertFirmAdmin): void {
    this.error.set(null);
    this.expertFirmsService.remove(firm.id).subscribe({
      next: () => this.reload(),
      error: (err: HttpErrorResponse) =>
        this.error.set(err.error?.detail || 'No se pudo borrar el perito'),
    });
  }

  protected branchLabel(firm: ExpertFirmAdmin): string {
    return firm.branchName ?? 'Todos los ramos';
  }
}
