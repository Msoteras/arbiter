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
 * Wraps ECharts so screens never use the library directly. A canvas says nothing to a screen
 * reader, so `description` is required: the same figures as the chart, in words.
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
  readonly description = input.required<string>();
  readonly tall = input(false);

  private chart: ECharts | null = null;

  /**
   * Not redundant with ngx-echarts' `autoResize`: the directive ignores its ResizeObserver's first
   * fire, which is the one carrying the real width when the chart initializes inside an animating
   * card. It also reacts inside `requestAnimationFrame`, which background tabs freeze.
   */
  constructor() {
    const host = inject(ElementRef<HTMLElement>).nativeElement as HTMLElement;
    const observer = new ResizeObserver(() => this.chart?.resize());
    observer.observe(host);
    inject(DestroyRef).onDestroy(() => observer.disconnect());
  }

  protected onChartInit(chart: ECharts): void {
    this.chart = chart;
    // The container may have grown between the directive creating the chart and this callback.
    chart.resize();
  }
}
