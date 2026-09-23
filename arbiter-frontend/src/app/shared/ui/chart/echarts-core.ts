import * as echarts from 'echarts/core';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

/**
 * Custom ECharts build with only the chart types the dashboard uses: importing the whole package
 * adds about 1 MB of JS. Lazy-loaded from `app.config.ts`, so it stays out of the initial bundle.
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
