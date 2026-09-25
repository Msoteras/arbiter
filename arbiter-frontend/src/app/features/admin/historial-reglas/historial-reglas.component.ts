import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';

import { documentTypeLabel, riskFactorLabel } from '../../../core/models/business-rules';
import { RISK_BANDS, RiskBand, riskBandLabel } from '../../../core/models/risk-band';
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
  RULE_FIELD_LABELS_BY_TYPE,
  RULE_TYPE_LABELS,
  RULE_VALUE_LABELS,
  RuleChangeEntry,
  RuleFieldChange,
  RuleHistoryService,
} from '../rule-history.service';
import { fadeInUp } from '../../../shared/animations';

/** Must match rules-service `RuleChangeHistoryService.LIST_SEPARATOR`. */
const LIST_SEPARATOR = ' · ';

/**
 * Fields the backend serializes as a joined list. Diffed item by item: striking the whole old list
 * and repeating the whole new one leaves the referente comparing two sentences by eye.
 */
const LIST_FIELDS = new Set([
  'criteria',
  'exclusions',
  'businessRules',
  'requiredDocumentTypes',
  'excludedClaimCauseIds',
  'includedClaimCauseIds',
  'claimCauseIds',
  'configuration',
]);

/** Fractions the configuration screens edit as percentages: Fast Track cap, factor weight, band cut. */
const PERCENT_FIELDS = new Set(['maxClaimedAmountRatio', 'weight', 'minScoreInclusive']);

/** Keys that identify a list element; the element is already named by the row's qualifier. */
const IDENTITY_FIELDS = new Set(['factorId', 'band']);

interface ValueChange {
  kind: 'value';
  label: string;
  qualifier: string | null;
  previous: string;
  next: string;
}

/** Also used for a scalar present on one side only: it was added or removed, not changed. */
interface ListChange {
  kind: 'list';
  label: string;
  qualifier: string | null;
  removed: string[];
  added: string[];
  /** Items present on both sides, counted rather than repeated. */
  kept: number;
}

type FieldChange = ValueChange | ListChange;

interface HistoryRow {
  entry: RuleChangeEntry;
  title: string;
  author: string | null;
  scope: string | null;
  changedAt: string;
  fields: FieldChange[];
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
      fields: entry.changes
        // The element's own key (`factors[fraud_history].factorId`) repeats the qualifier.
        .filter((change) => !IDENTITY_FIELDS.has(this.baseField(change.field)))
        .map((change) => this.toField(change, entry.ruleType)),
    };
  }

  private toField(change: RuleFieldChange, ruleType: string): FieldChange {
    const base = this.baseField(change.field);
    // Unlabeled fields fall back to the last path segment; the factor code is the qualifier.
    const label = RULE_FIELD_LABELS_BY_TYPE[ruleType]?.[base] ?? RULE_FIELD_LABELS[base] ?? base;
    const qualifier = this.qualifierOf(change);
    if (LIST_FIELDS.has(base)) {
      const before = this.splitList(change.previousValue, base);
      const after = this.splitList(change.newValue, base);
      const removed = before.filter((item) => !after.includes(item));
      const added = after.filter((item) => !before.includes(item));
      // A pure reorder has nothing to list as added or removed: show it as a plain before/after.
      if (removed.length > 0 || added.length > 0) {
        const kept = after.length - added.length;
        return { kind: 'list', label, qualifier, removed, added, kept };
      }
    }
    // A side with no value means the field didn't exist there (a factor or option added to the
    // rule later, or removed): said as "Se agregó" / "Se quitó", like the list items.
    const absent = (value: string | null) => value === null || value === '';
    if (absent(change.previousValue) !== absent(change.newValue)) {
      const value = absent(change.previousValue)
        ? this.renderValue(change.newValue, base)
        : this.renderValue(change.previousValue, base);
      return absent(change.previousValue)
        ? { kind: 'list', label, qualifier, removed: [], added: [value], kept: 0 }
        : { kind: 'list', label, qualifier, removed: [value], added: [], kept: 0 };
    }
    return {
      kind: 'value',
      label,
      qualifier,
      previous: this.renderValue(change.previousValue, base),
      next: this.renderValue(change.newValue, base),
    };
  }

  private splitList(value: string | null, field: string): string[] {
    if (value === null || value.trim() === '') {
      return [];
    }
    return value
      .split(LIST_SEPARATOR)
      .map((item) => item.trim())
      .filter((item) => item !== '')
      .map((item) => (field === 'requiredDocumentTypes' ? documentTypeLabel(item) : item));
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

  /** Bracketed suffix of a list field (factor code or band), by its label. */
  private qualifierOf(change: RuleFieldChange): string | null {
    const match = /\[([^\]]+)\]/.exec(change.field);
    if (!match) {
      return null;
    }
    const key = match[1];
    return RISK_BANDS.includes(key as RiskBand)
      ? riskBandLabel(key as RiskBand)
      : riskFactorLabel(key);
  }

  private renderValue(value: string | null, field: string): string {
    if (value === null || value === '') {
      return '—';
    }
    // Stored as fractions (0..1); the configuration screens show them as percentages.
    const n = Number(value);
    if (PERCENT_FIELDS.has(field) && Number.isFinite(n)) {
      return `${Math.round(n * 1000) / 10}%`;
    }
    if (value === 'true') {
      return 'Sí';
    }
    if (value === 'false') {
      return 'No';
    }
    const enumLabel = RULE_VALUE_LABELS[field]?.[value];
    if (enumLabel) {
      return enumLabel;
    }
    if (field === 'requiredDocumentTypes') {
      return value.split(LIST_SEPARATOR).map(documentTypeLabel).join(LIST_SEPARATOR);
    }
    return value;
  }
}
