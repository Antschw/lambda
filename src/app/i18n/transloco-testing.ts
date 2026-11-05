import { TranslocoTestingModule, TranslocoTestingOptions } from '@jsverse/transloco';

import en from '../../../public/i18n/en.json';
import fr from '../../../public/i18n/fr.json';
import { appTranslocoConfig } from './i18n.config';

/**
 * Module Transloco pour les tests unitaires : les vraies traductions sont
 * chargées de façon synchrone, sans requête HTTP.
 */
export function getTranslocoModule(options: TranslocoTestingOptions = {}) {
  return TranslocoTestingModule.forRoot({
    langs: { en, fr },
    translocoConfig: appTranslocoConfig,
    preloadLangs: true,
    ...options,
  });
}
