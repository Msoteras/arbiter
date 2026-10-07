import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { BranchOption, BranchesService } from '../branches.service';
import { ProviderAdmin, ProviderRequest, ProvidersService } from '../providers.service';
import {
  PROVIDER_TYPE_OPTIONS,
  ProviderType,
  providerTypeLabel,
  branchesLabel,
} from '../../../core/models/derivation';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { CheckboxComponent } from '../../../shared/ui/checkbox/checkbox.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';

/** `id` null = new provider. */
interface ProviderDraft extends ProviderRequest {
  id: number | null;
}

/**
 * Insurer-wide catalog of external providers (who a case can be referred to), each covering some
 * branches or all of them. The amount that enables a referral is a rule in rules-service.
 */
@Component({
  selector: 'app-providers-config',
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
  templateUrl: './providers-config.component.html',
  styleUrl: './providers-config.component.scss',
})
export class ProvidersConfigComponent {
  private readonly providersService = inject(ProvidersService);
  private readonly branchesService = inject(BranchesService);

  protected readonly providers = signal<ProviderAdmin[]>([]);
  protected readonly branches = signal<BranchOption[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /** null = no row being edited. */
  protected readonly draft = signal<ProviderDraft | null>(null);
  protected readonly saving = signal(false);

  constructor() {
    this.reload();
    this.branchesService.list().subscribe({ next: (list) => this.branches.set(list) });
  }

  private reload(): void {
    this.loading.set(true);
    this.providersService.list().subscribe({
      next: (list) => {
        this.providers.set(list);
        this.loading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.loading.set(false);
        this.error.set(err.error?.detail || 'No se pudo cargar el catálogo de proveedores');
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
      branchIds: [],
      active: true,
      providerType: 'ESTUDIO_LIQUIDADOR',
    });
  }

  protected edit(provider: ProviderAdmin): void {
    this.error.set(null);
    this.draft.set({
      id: provider.id,
      name: provider.name,
      email: provider.email,
      zone: provider.zone ?? '',
      branchIds: provider.branches.map((b) => b.id),
      active: provider.active,
      providerType: provider.providerType,
    });
  }

  protected cancel(): void {
    this.draft.set(null);
  }

  protected setField<K extends keyof ProviderDraft>(field: K, value: ProviderDraft[K]): void {
    const current = this.draft();
    if (current) {
      this.draft.set({ ...current, [field]: value });
    }
  }

  protected setProviderType(value: string): void {
    this.setField('providerType', value as ProviderType);
  }

  /** None checked is a valid answer: a generalist covering every branch. */
  protected toggleBranch(branchId: number, checked: boolean): void {
    const current = this.draft();
    if (!current) {
      return;
    }
    const others = current.branchIds.filter((id) => id !== branchId);
    this.setField('branchIds', checked ? [...others, branchId] : others);
  }

  protected readonly canSave = computed(() => {
    const d = this.draft();
    // Email is the only channel to the provider: without it a referral goes nowhere.
    return !!d && d.name.trim().length > 0 && d.email.trim().length > 0;
  });

  protected save(): void {
    const d = this.draft();
    if (!d || !this.canSave()) {
      return;
    }
    const request: ProviderRequest = {
      name: d.name.trim(),
      email: d.email.trim(),
      zone: d.zone?.trim() || null,
      branchIds: d.branchIds,
      active: d.active,
      providerType: d.providerType,
    };
    this.saving.set(true);
    this.error.set(null);
    const call =
      d.id == null
        ? this.providersService.create(request)
        : this.providersService.update(d.id, request);
    call.subscribe({
      next: () => {
        this.saving.set(false);
        this.draft.set(null);
        this.reload();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.error.set(err.error?.detail || 'No se pudo guardar el proveedor');
      },
    });
  }

  /** A 409 (provider has referrals) explains it must be deactivated instead; shown as is. */
  protected remove(provider: ProviderAdmin): void {
    this.error.set(null);
    this.providersService.remove(provider.id).subscribe({
      next: () => this.reload(),
      error: (err: HttpErrorResponse) =>
        this.error.set(err.error?.detail || 'No se pudo borrar el proveedor'),
    });
  }

  protected readonly branchesLabel = branchesLabel;
}
