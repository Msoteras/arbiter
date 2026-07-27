import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Tabla del design system. El contenido (thead/tbody con th/td reales) se
 * proyecta tal cual HTML nativo — este componente solo aporta el look
 * estándar (header tenue en mayúsculas, filas separadas por borde).
 * ::ng-deep es necesario porque ese contenido llega vía <ng-content>: la
 * encapsulación de estilos de Angular no lo alcanza de otra forma. Queda
 * acotado al host (no se filtra fuera de esta instancia).
 */
@Component({
  selector: 'app-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <table class="table">
      <ng-content />
    </table>
  `,
  styles: `
    :host { display: block; overflow-x: auto; }
    .table { width: 100%; border-collapse: collapse; font-size: var(--font-size-body); }
    :host ::ng-deep .table th,
    :host ::ng-deep .table td {
      text-align: left;
      padding: var(--space-3) var(--space-4);
      white-space: nowrap;
    }
    :host ::ng-deep .table thead th {
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--text-muted);
      background: var(--surface-head);
      border-bottom: 1px solid var(--border-default);
    }
    :host ::ng-deep .table tbody tr:not(:last-child) td {
      border-bottom: 1px solid var(--border-subtle);
    }
  `,
})
export class TableComponent {}
