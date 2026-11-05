import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideTransloco } from '@jsverse/transloco';

import { appTranslocoConfig } from './i18n/transloco-config';
import { TranslocoHttpLoader } from './i18n/transloco-loader';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // HttpClient (utilisé par le loader) est fourni par défaut depuis Angular 21 :
    // provideHttpClient() n'est nécessaire que pour ajouter des intercepteurs.
    provideTransloco({
      config: appTranslocoConfig,
      loader: TranslocoHttpLoader,
    }),
  ],
};
