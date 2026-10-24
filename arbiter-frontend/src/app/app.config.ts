import { registerLocaleData } from '@angular/common';
import localeEsAr from '@angular/common/locales/es-AR';
import {
  ApplicationConfig,
  LOCALE_ID,
  provideBrowserGlobalErrorListeners,
  inject,
  provideZoneChangeDetection,
} from '@angular/core';
import { Router, provideRouter, withViewTransitions } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideEchartsCore } from 'ngx-echarts';

import { routes } from './app.routes';
import { authInterceptor } from './core/http/auth.interceptor';
import { onboardingInterceptor } from './core/http/onboarding.interceptor';
import { cacheInvalidationInterceptor } from './core/http/cache-invalidation.interceptor';
import { upstreamRetryInterceptor } from './core/http/upstream-retry.interceptor';
import { httpActivityInterceptor } from './core/http/http-activity';

// Without this Angular's pipes (percent, number, date) format as en-US — "14.3%" next to the
// "12,5%" that the Intl-based helpers already print.
registerLocaleData(localeEsAr);

export const appConfig: ApplicationConfig = {
  providers: [
    { provide: LOCALE_ID, useValue: 'es-AR' },
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(
      routes,
      withViewTransitions({
        skipInitialTransition: true,
        // A filter or a tab that only rewrites the query is the same screen: no crossfade.
        onViewTransitionCreated: ({ transition }) => {
          const router = inject(Router);
          const target = router.currentNavigation()?.finalUrl;
          const sameScreen =
            target &&
            router.isActive(target, {
              paths: 'exact',
              matrixParams: 'exact',
              queryParams: 'ignored',
              fragment: 'ignored',
            });
          if (sameScreen) transition.skipTransition();
        },
      }),
    ),
    provideHttpClient(
      withInterceptors([
        httpActivityInterceptor,
        authInterceptor,
        upstreamRetryInterceptor,
        cacheInvalidationInterceptor,
        onboardingInterceptor,
      ]),
    ),
    provideAnimationsAsync(),
    // Lazy import keeps ECharts out of the initial bundle; echarts-core registers only the chart
    // types in use.
    provideEchartsCore({ echarts: () => import('./shared/ui/chart/echarts-core') }),
  ],
};
