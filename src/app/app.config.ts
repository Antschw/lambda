import { provideHttpClient, withFetch } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideTransloco } from '@jsverse/transloco';

import { routes } from './app.routes';
import { appTranslocoConfig } from './i18n/i18n.config';
import { TranslocoHttpLoader } from './i18n/transloco-loader';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // HttpClient est utilisé par le loader. withFetch() : API fetch plutôt que XHR
    // (recommandé par Angular, et comportement par défaut à partir d'Angular 22).
    provideHttpClient(withFetch()),
    provideTransloco({
      config: appTranslocoConfig,
      loader: TranslocoHttpLoader,
    }),
  ],
};
