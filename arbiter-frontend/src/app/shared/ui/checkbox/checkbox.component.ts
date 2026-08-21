import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

/**
 * Checkbox del design system.
 *
 * A diferencia de app-select, acá el control nativo se conserva: `accent-color` ya deja
 * pintar la marca con el color del sistema, y el nativo trae gratis el foco por teclado,
 * el estado indeterminado y el soporte de lectores de pantalla. Lo que aporta el
 * componente es el tamaño, el área de toque y la etiqueta clickeable, que es lo que se
 * venía copiando a mano en cada pantalla.
 *
 * Valor two-way vía model() → `[(checked)]`.
 */
@Component({
  selector: 'app-checkbox',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <label class="cb">
      <input
        type="checkbox"
        [checked]="checked()"
        [disabled]="disabled()"
        (change)="onToggle($event)" />
      <span class="cb-label"><ng-content /></span>
    </label>
  `,
  styles: `
    :host { display: block; }

    .cb {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      /* 40px de alto mínimo: el cuadrito son 18px, chico para tocar cómodo. */
      min-height: 40px;
      font-size: var(--font-size-body);
      color: var(--text-primary);
      cursor: pointer;
    }

    .cb:has(input:disabled) { cursor: default; opacity: 0.55; }

    input[type='checkbox'] {
      width: 18px;
      height: 18px;
      flex-shrink: 0;
      /* --accent-strong y no --accent: el color del tilde lo elige el navegador por contraste
         contra el relleno, y con el teal de marca (claro) le toca un tilde oscuro que se lee mal.
         Con el teal fuerte el tilde sale blanco, que además es como está en el diseño. */
      accent-color: var(--accent-strong);
      cursor: inherit;
    }

    .cb-label { text-wrap: pretty; }
  `,
})
export class CheckboxComponent {
  readonly checked = model(false);
  readonly disabled = input(false);

  protected onToggle(event: Event): void {
    this.checked.set((event.target as HTMLInputElement).checked);
  }
}
