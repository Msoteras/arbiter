import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { Observable, forkJoin } from 'rxjs';

import { amountInputDisplay, amountInputLabel, amountInputValue } from '../../../core/util/money';
import { ClaimCauseOption } from '../coverage-exclusions.service';
import { DerivationRulesService } from '../derivation-rules.service';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { InfoTipComponent } from '../../../shared/ui/info-tip/info-tip.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { SaveBarComponent } from '../../../shared/ui/save-bar/save-bar.component';
import { SwitchComponent } from '../../../shared/ui/switch/switch.component';
import { fadeInUp } from '../../../shared/animations';

/** Both rules of the branch as edited. The amount is the typed value (`612500.5`), '' when empty. */
interface DerivationsDraft {
  expertEnabled: boolean;
  expertAmount: string;
  /** Empty means the branch doesn't derive to repair: there is no separate switch for it. */
  repairCauseIds: number[];
}

/**
 * A branch's derivation rules: from what claimed amount it derives to an expert, and for which
 * claim causes to a repair shop. One draft and one save for both, which only sends what changed.
 */
@Component({
  selector: 'app-derivations-config',
  imports: [
    ButtonComponent,
    CardComponent,
    InfoTipComponent,
    InlineLoadingComponent,
    InputComponent,
    SaveBarComponent,
    SwitchComponent,
  ],
  animations: [fadeInUp],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './derivations-config.component.html',
  styleUrl: './derivations-config.component.scss',
})
export class DerivationsConfigComponent {
  private readonly service = inject(DerivationRulesService);

  readonly branchId = input.required<number>();
  /** The branch's claim causes, already loaded by the rules screen. */
  readonly claimCauses = input<ClaimCauseOption[]>([]);

  protected readonly draft = signal<DerivationsDraft | null>(null);
  /** Last state confirmed by the backend; unsaved changes are measured against it. */
  private readonly persisted = signal<DerivationsDraft | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadFailed = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly amountInputDisplay = amountInputDisplay;

  private readonly expertDirty = computed(() => {
    const current = this.draft();
    const saved = this.persisted();
    return (
      current != null &&
      saved != null &&
      (current.expertEnabled !== saved.expertEnabled || current.expertAmount !== saved.expertAmount)
    );
  });

  private readonly repairDirty = computed(() => {
    const current = this.draft();
    const saved = this.persisted();
    return (
      current != null &&
      saved != null &&
      current.repairCauseIds.join() !== saved.repairCauseIds.join()
    );
  });

  protected readonly dirty = computed(() => this.expertDirty() || this.repairDirty());

  protected readonly expertInvalid = computed(() => {
    const d = this.draft();
    return d != null && d.expertEnabled && d.expertAmount === '';
  });

  constructor() {
    effect(() => this.load(this.branchId()));
  }

  protected reload(): void {
    this.load(this.branchId());
  }

  private load(branchId: number): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.error.set(null);
    this.draft.set(null);
    this.persisted.set(null);
    forkJoin({
      expert: this.service.getExpert(branchId),
      repair: this.service.getRepair(branchId),
    }).subscribe({
      next: ({ expert, repair }) => {
        // A slow answer for a branch the referente already left.
        if (branchId !== this.branchId()) {
          return;
        }
        const loaded: DerivationsDraft = {
          expertEnabled: expert.enabled,
          expertAmount: expert.minClaimedAmount == null ? '' : String(expert.minClaimedAmount),
          // A rule that is off keeps its old selection stored; here it reads as nothing picked.
          repairCauseIds: repair.enabled ? sorted(repair.claimCauseIds) : [],
        };
        this.draft.set(loaded);
        this.persisted.set(structuredClone(loaded));
        this.loading.set(false);
      },
      error: () => {
        if (branchId !== this.branchId()) {
          return;
        }
        this.loading.set(false);
        this.loadFailed.set(true);
      },
    });
  }

  protected expertAmountLabel(): string {
    return amountInputLabel(this.draft()?.expertAmount ?? '');
  }

  protected toggleExpert(): void {
    this.patch((d) => ({ ...d, expertEnabled: !d.expertEnabled }));
  }

  protected setExpertAmount(typed: string): void {
    this.patch((d) => ({ ...d, expertAmount: amountInputValue(typed) }));
  }

  protected isRepairCause(causeId: number): boolean {
    return this.draft()?.repairCauseIds.includes(causeId) ?? false;
  }

  protected toggleRepairCause(causeId: number, checked: boolean): void {
    this.patch((d) => ({
      ...d,
      repairCauseIds: sorted(
        checked ? [...d.repairCauseIds, causeId] : d.repairCauseIds.filter((id) => id !== causeId),
      ),
    }));
  }

  private patch(change: (draft: DerivationsDraft) => DerivationsDraft): void {
    this.draft.update((d) => (d ? change(d) : d));
    this.error.set(null);
  }

  protected discard(): void {
    const saved = this.persisted();
    if (saved) {
      this.draft.set(structuredClone(saved));
    }
    this.error.set(null);
  }

  protected save(): void {
    const d = this.draft();
    if (!d || this.expertInvalid()) {
      return;
    }
    const branchId = this.branchId();
    const requests: Observable<unknown>[] = [];
    if (this.expertDirty()) {
      requests.push(
        this.service.saveExpert(branchId, {
          enabled: d.expertEnabled,
          minClaimedAmount: d.expertAmount === '' ? null : Number(d.expertAmount),
        }),
      );
    }
    if (this.repairDirty()) {
      requests.push(
        this.service.saveRepair(branchId, {
          enabled: d.repairCauseIds.length > 0,
          claimCauseIds: d.repairCauseIds,
        }),
      );
    }
    if (requests.length === 0) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    forkJoin(requests).subscribe({
      next: () => {
        this.saving.set(false);
        this.persisted.set(structuredClone(d));
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        // One of the two may have been saved: reloading would drop what the other still has typed.
        this.error.set(err.error?.detail || 'No se pudieron guardar las derivaciones.');
      },
    });
  }
}

function sorted(ids: number[]): number[] {
  return [...ids].sort((a, b) => a - b);
}
