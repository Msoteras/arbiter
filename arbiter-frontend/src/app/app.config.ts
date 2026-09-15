import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimations } from '@angular/platform-browser/animations';
import { provideEchartsCore } from 'ngx-echarts';

import { routes } from './app.routes';
import { authInterceptor } from './core/http/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    // Habilita @angular/animations (transiciones de entrada/stagger de los componentes).
    provideAnimations(),
    // ECharts, en diferido: el import() lo saca del bundle inicial, así que la librería recién
    // se descarga cuando alguien abre una pantalla con gráficos. `echarts-core` registra sólo
    // los tipos que usamos, en vez del catálogo entero.
    provideEchartsCore({ echarts: () => import('./shared/ui/chart/echarts-core') }),
  ],
};
