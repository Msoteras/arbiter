import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';

import { formatDateTime } from '../../../core/util/datetime';
import { BadgeComponent } from '../../../shared/ui/badge/badge.component';
import { ButtonComponent } from '../../../shared/ui/button/button.component';
import { CardComponent } from '../../../shared/ui/card/card.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state/empty-state.component';
import { InlineLoadingComponent } from '../../../shared/ui/inline-loading/inline-loading.component';
import { InputComponent } from '../../../shared/ui/input/input.component';
import { PaginationComponent } from '../../../shared/ui/pagination/pagination.component';
import { SelectComponent, SelectOption } from '../../../shared/ui/select/select.component';
import { BranchOption, BranchesService } from '../branches.service';
import {
  RULE_FIELD_LABELS,
  RULE_TYPE_LABELS,
  RuleChangeEntry,
  RuleFieldChange,
  RuleHistoryService,
} from '../rule-history.service';
import { fadeInUp } from '../../../shared/animations';

interface HistoryRow {
  entry: RuleChangeEntry;
  title: string;
  author: string | null;
  scope: string | null;
  changedAt: string;
  heldSince: string;
  fields: { label: string; qualifier: string | null; previous: string; next: string }[];
}

/**
 * Read-only on purpose (SSN Disposition 2/2023 audit): no revert, edit or delete. Going back to a
 * previous value means configuring the rule again, which is recorded as one more change.
 */
@Component({
  selector: 'app-historial-reglas',
  imports: [
    BadgeComponent,
    ButtonComponent,
    CardComponent,
    EmptyStateComponent,
    InlineLoadingComponent,
    InputComponent,
    PaginationComponent,
    SelectComponent,
  ],
  animations: [fadeInUp],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './historial-reglas.component.html',
  styleUrl: './historial-reglas.component.scss',
})
export class HistorialReglasComponent {
  private readonly historyService = inject(RuleHistoryService);
  private readonly branchesService = inject(BranchesService);

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly rows = signal<HistoryRow[]>([]);
  protected readonly page = signal(0);
  protected readonly size = signal(20);
  protected readonly totalElements = signal(0);
  protected readonly totalPages = signal(0);

  protected readonly ruleType = signal('');
  protected readonly branchId = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');

  protected readonly ruleTypeOptions = signal<SelectOption[]>([]);
  protected readonly branchOptions = signal<SelectOption[]>([]);

  protected readonly hasFilters = computed(
    () => !!this.ruleType() || !!this.branchId() || !!this.from() || !!this.to(),
  );

  constructor() {
    this.loadFilters();
    this.load();
  }

  /** Rule types come from the history itself, so no filter option can only return empty. */
  private loadFilters(): void {
    this.historyService.ruleTypes().subscribe({
      next: (types) =>
        this.ruleTypeOptions.set(
          types.map((type) => ({ value: type, label: RULE_TYPE_LABELS[type] ?? type })),
        ),
      error: () => this.ruleTypeOptions.set([]),
    });
    this.branchesService.list().subscribe({
      next: (branches: BranchOption[]) =>
        this.branchOptions.set(branches.map((b) => ({ value: String(b.id), label: b.name }))),
      error: () => this.branchOptions.set([]),
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.historyService
      .find({
        ruleType: this.ruleType() || undefined,
        branchId: this.branchId() ? Number(this.branchId()) : undefined,
        from: this.from() || undefined,
        to: this.to() || undefined,
        page: this.page(),
        size: this.size(),
      })
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (response) => {
          this.rows.set(response.content.map((entry) => this.toRow(entry)));
          this.totalElements.set(response.totalElements);
          this.totalPages.set(response.totalPages);
        },
        error: (err: HttpErrorResponse) =>
          this.error.set(
            err.status === 0
              ? 'No se pudo contactar al servidor.'
              : 'No se pudo cargar el historial de cambios.',
          ),
      });
  }

  /** Back to the first page: page 4 of a new result may not exist. */
  protected applyFilters(): void {
    this.page.set(0);
    this.load();
  }

  protected clearFilters(): void {
    this.ruleType.set('');
    this.branchId.set('');
    this.from.set('');
    this.to.set('');
    this.applyFilters();
  }

  protected onPageChange(page: number): void {
    this.page.set(page);
    this.load();
  }

  protected onSizeChange(size: number): void {
    this.size.set(size);
    this.page.set(0);
    this.load();
  }

  private toRow(entry: RuleChangeEntry): HistoryRow {
    return {
      entry,
      title: RULE_TYPE_LABELS[entry.ruleType] ?? entry.ruleType,
      author: entry.author,
      scope: this.scopeOf(entry),
      changedAt: formatDateTime(entry.changedAt),
      heldSince: formatDateTime(entry.previousValidFrom),
      fields: entry.changes.map((change) => {
        const base = this.baseField(change.field);
        return {
          // Unlabeled fields fall back to the last path segment; the factor code is the qualifier.
          label: RULE_FIELD_LABELS[base] ?? base,
          qualifier: this.qualifierOf(change),
          previous: this.renderValue(change.previousValue, base),
          next: this.renderValue(change.newValue, base),
        };
      }),
    };
  }

  /** Insurer-wide scope is stated explicitly so an empty scope isn't read as missing data. */
  private scopeOf(entry: RuleChangeEntry): string | null {
    if (entry.coverageName) {
      return `${entry.branchName ?? 'Ramo'} · ${entry.coverageName}`;
    }
    if (entry.coverageId) {
      // Unresolved name: the coverage was deleted after the change. The id still locates it.
      return `${entry.branchName ?? 'Ramo'} · cobertura #${entry.coverageId}`;
    }
    return entry.branchName;
  }

  /** Labels `factors[IMAGE_REUSED].weight` by its last segment; the key is shown apart. */
  private baseField(field: string): string {
    const last = field.split('.').pop() ?? field;
    return last.replace(/\[.*\]$/, '');
  }

  /** Bracketed suffix of a list field, if any (factor code, band). */
  private qualifierOf(change: RuleFieldChange): string | null {
    const match = /\[([^\]]+)\]/.exec(change.field);
    return match ? match[1] : null;
  }

  private renderValue(value: string | null, field: string): string {
    if (value === null || value === '') {
      return '—';
    }
    // Stored as fractions (0..1); the configuration screens show them on a 0..100 scale.
    const n = Number(value);
    if (field === 'maxClaimedAmountRatio' && Number.isFinite(n)) {
      return `${Math.round(n * 1000) / 10}%`;
    }
    if (field === 'minScoreInclusive' && Number.isFinite(n)) {
      return String(Math.round(n * 100));
    }
    if (value === 'true') {
      return 'Sí';
    }
    if (value === 'false') {
      return 'No';
    }
    return value;
  }
}
