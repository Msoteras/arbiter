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

/** Un cambio con lo que la vista necesita ya resuelto, para no calcular nada en el template. */
interface HistoryRow {
  entry: RuleChangeEntry;
  title: string;
  scope: string | null;
  changedAt: string;
  heldSince: string;
  fields: { label: string; qualifier: string | null; previous: string; next: string }[];
}

/**
 * Historial de cambios de las reglas de la aseguradora, para el referente.
 *
 * <p>Es la contracara de lectura de la auditoría que los servicios de reglas ya venían escribiendo
 * en cada guardado: quién cambió qué, cuándo y de qué valor a cuál. La pide la Disposición SSN
 * 2/2023 y el documento de arquitectura la nombra como un registro "inmutable y consultable por el
 * referente de la aseguradora" (sección 8, Seguridad).
 *
 * <p><b>Solo lectura, a propósito.</b> No hay revertir, editar ni borrar: un historial que se puede
 * tocar no sirve como auditoría. Volver a un valor anterior se hace configurando la regla otra vez
 * en su pantalla, y ese acto queda registrado como un cambio más — que es exactamente lo que tiene
 * que pasar.
 *
 * <p>Vive dentro de la pantalla de reglas, como sección de "Reglas generales": no es de ningún ramo
 * en particular (cruza todos) y el referente la consulta en el mismo lugar donde configura.
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

  /**
   * Los tipos salen del propio historial y los ramos del catálogo: ofrecer un tipo que la
   * aseguradora nunca editó sería ofrecer un filtro que devuelve vacío.
   */
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

  /** Cualquier filtro vuelve a la primera página: la 4 de un resultado nuevo puede no existir. */
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
      scope: this.scopeOf(entry),
      changedAt: formatDateTime(entry.changedAt),
      heldSince: formatDateTime(entry.previousValidFrom),
      fields: entry.changes.map((change) => ({
        label: RULE_FIELD_LABELS[this.baseField(change.field)] ?? change.field,
        qualifier: this.qualifierOf(change),
        previous: this.renderValue(change.previousValue),
        next: this.renderValue(change.newValue),
      })),
    };
  }

  /**
   * Dónde aplica la regla. La mayoría son de toda la aseguradora (Hard Stop, antecedente de fraude,
   * puntaje), así que decirlo explícito evita que el referente lea un cambio sin alcance y suponga
   * que le faltó un dato.
   */
  private scopeOf(entry: RuleChangeEntry): string | null {
    if (entry.coverageName) {
      return `${entry.branchName ?? 'Ramo'} · ${entry.coverageName}`;
    }
    if (entry.coverageId) {
      // El nombre no se resolvió: la cobertura fue borrada después del cambio. El id igual ubica
      // al referente, y callarlo sería peor — el cambio existió sobre algo.
      return `${entry.branchName ?? 'Ramo'} · cobertura #${entry.coverageId}`;
    }
    return entry.branchName;
  }

  /**
   * `factors[IMAGE_REUSED].weight` se etiqueta por su último tramo: la clave del elemento ya se
   * muestra aparte y no hay una etiqueta por cada factor posible.
   */
  private baseField(field: string): string {
    const last = field.split('.').pop() ?? field;
    return last.replace(/\[.*\]$/, '');
  }

  /** El sufijo entre corchetes de un campo de lista, si lo tiene (el código del factor, la banda). */
  private qualifierOf(change: RuleFieldChange): string | null {
    const match = /\[([^\]]+)\]/.exec(change.field);
    return match ? match[1] : null;
  }

  private renderValue(value: string | null): string {
    if (value === null || value === '') {
      return '—';
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
