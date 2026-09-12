import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  input,
} from '@angular/core';
import { ECharts, EChartsCoreOption } from 'echarts/core';
import { NgxEchartsDirective } from 'ngx-echarts';

/**
 * Un gráfico del tablero. Envuelve a ECharts para que los componentes no toquen la librería
 * directo: acá viven el alto (de los tokens, nunca px sueltos en la pantalla que lo usa), el
 * redimensionado y el texto alternativo.
 *
 * Un canvas no dice nada a un lector de pantalla, así que `description` es obligatorio y se
 * publica como el contenido accesible del gráfico: los mismos números que el dibujo, en palabras.
 * La pantalla que lo usa ya tiene los datos para armarla.
 */
@Component({
  selector: 'app-chart',
  imports: [NgxEchartsDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="wrap" [class.tall]="tall()">
      <div
        echarts
        class="canvas"
        role="img"
        [attr.aria-label]="description()"
        [options]="options()"
        (chartInit)="onChartInit($event)"
      ></div>
      <figcaption class="sr-only">{{ description() }}</figcaption>
    </figure>
  `,
  styles: `
    :host {
      display: block;
    }
    .wrap {
      margin: 0;
      height: var(--chart-h);
    }
    .wrap.tall {
      height: var(--chart-h-tall);
    }
    .canvas {
      width: 100%;
      height: 100%;
    }
  `,
})
export class ChartComponent {
  readonly options = input.required<EChartsCoreOption>();
  /** Lo que el gráfico dice, en texto. Lo lee el lector de pantalla en lugar del canvas. */
  readonly description = input.required<string>();
  /** Para la línea de tiempo, que necesita más aire vertical que las distribuciones. */
  readonly tall = input(false);

  private chart: ECharts | null = null;

  /**
   * El redimensionado propio no es redundante con el `autoResize` de ngx-echarts: hace falta
   * porque la directiva **descarta a propósito el primer aviso** de su ResizeObserver ("ignore
   * first fire on insertion"), y ese primero es justamente el que importa. El gráfico se inicializa
   * un tick después de entrar al DOM, mientras la tarjeta todavía está animando su aparición, así
   * que se dibuja contra un contenedor de unos pocos píxeles de ancho; el aviso que traía el ancho
   * real llega después y se descarta, y el canvas queda del tamaño equivocado para siempre.
   *
   * Además la directiva reacciona dentro de un `requestAnimationFrame`, que el navegador congela
   * en pestañas en segundo plano: ahí ni siquiera el segundo aviso alcanzaba.
   */
  constructor() {
    const host = inject(ElementRef<HTMLElement>).nativeElement as HTMLElement;
    const observer = new ResizeObserver(() => this.chart?.resize());
    observer.observe(host);
    inject(DestroyRef).onDestroy(() => observer.disconnect());
  }

  protected onChartInit(chart: ECharts): void {
    this.chart = chart;
    // Por si el contenedor ya creció entre que la directiva armó el gráfico y este callback.
    chart.resize();
  }
}
