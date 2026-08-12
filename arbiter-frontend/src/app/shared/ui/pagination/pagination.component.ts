import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

/**
 * Pager reutilizable para listados paginados server-side (Spring Data: `page` 0-based).
 * Muestra el rango visible ("1–20 de 57"), navegación anterior/siguiente y un selector de
 * tamaño de página. No conoce nada del dominio — cualquier listado paginado puede usarlo.
 */
@Component({
  selector: 'app-pagination',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pager">
      <span class="t-note summary">
        @if (totalElements() === 0) {
          Sin resultados
        } @else {
          {{ rangeStart() }}–{{ rangeEnd() }} de {{ totalElements() }}
        }
      </span>

      <div class="controls">
        <label class="size-picker">
          <span class="t-note">Por página</span>
          <select
            class="size-select"
            [value]="size()"
            (change)="sizeChange.emit(+$any($event.target).value)"
          >
            @for (s of sizeOptions(); track s) {
              <!-- [selected] además de [value] en el select: sin esto, el <select> nativo se
                   renderiza antes que sus <option> y cae en la primera (mostraba "10" aunque el
                   tamaño real fuera 20). -->
              <option [value]="s" [selected]="s === size()">{{ s }}</option>
            }
          </select>
        </label>

        <button
          type="button"
          class="nav-btn"
          [disabled]="page() === 0"
          (click)="pageChange.emit(page() - 1)"
        >‹ Anterior</button>

        <span class="t-note page-indicator">Página {{ page() + 1 }} de {{ totalPages() || 1 }}</span>

        <button
          type="button"
          class="nav-btn"
          [disabled]="page() + 1 >= totalPages()"
          (click)="pageChange.emit(page() + 1)"
        >Siguiente ›</button>
      </div>
    </div>
  `,
  styles: `
    :host { display: block; margin-top: var(--space-4); }
    .pager {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3);
    }
    .controls { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
    .size-picker { display: flex; align-items: center; gap: var(--space-2); }
    .size-select {
      font: inherit;
      font-size: var(--font-size-sm);
      padding: var(--space-1) var(--space-2);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      background: var(--surface);
      color: var(--text-secondary);
    }
    .nav-btn {
      font: inherit;
      font-size: var(--font-size-sm);
      padding: var(--space-1) var(--space-3);
      border: 1px solid var(--border-control);
      border-radius: var(--radius-ctl);
      background: var(--surface);
      color: var(--text-secondary);
      cursor: pointer;
    }
    .nav-btn:hover:not(:disabled) { border-color: var(--action-secondary-border-hover); }
    .nav-btn:disabled { opacity: 0.4; cursor: not-allowed; }
    .page-indicator { white-space: nowrap; }
  `,
})
export class PaginationComponent {
  readonly page = input(0);
  readonly totalPages = input(0);
  readonly totalElements = input(0);
  readonly size = input(20);
  readonly sizeOptions = input<number[]>([10, 20, 50]);

  readonly pageChange = output<number>();
  readonly sizeChange = output<number>();

  protected readonly rangeStart = computed(() => this.page() * this.size() + 1);
  protected readonly rangeEnd = computed(() =>
    Math.min((this.page() + 1) * this.size(), this.totalElements()),
  );
}
