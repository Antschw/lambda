import { isDevMode } from '@angular/core';
import { translocoConfig } from '@jsverse/transloco';

/** Langues supportées par l'application (source unique de vérité). */
export const AVAILABLE_LANGS = ['en', 'fr'] as const;

/** Union des codes de langue supportés : `'en' | 'fr'`. */
export type Lang = (typeof AVAILABLE_LANGS)[number];

/** Configuration Transloco partagée par l'application et les tests. */
export const appTranslocoConfig = translocoConfig({
  availableLangs: [...AVAILABLE_LANGS],
  defaultLang: 'en' satisfies Lang,
  // Met à jour les vues quand la langue change à l'exécution.
  reRenderOnLangChange: true,
  // Masque les avertissements de clés manquantes en production.
  prodMode: !isDevMode(),
});
