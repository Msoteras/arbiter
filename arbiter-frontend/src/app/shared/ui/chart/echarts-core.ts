import * as echarts from 'echarts/core';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

/**
 * Build a medida de ECharts: sólo los tres tipos de gráfico que el tablero usa. Importar el
 * paquete entero (`import('echarts')`, que es lo que muestra la documentación) trae mapas,
 * treemaps, gauges y el resto del catálogo — cerca de un megabyte de JS para dibujar tres
 * gráficos. Lo que se registra acá es lo único que el bundle termina cargando.
 *
 * Se carga en diferido desde `app.config.ts`, así que nada de esto entra al bundle inicial:
 * recién llega cuando alguien abre el tablero.
 */
echarts.use([
  BarChart,
  LineChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer,
]);

export * from 'echarts/core';
