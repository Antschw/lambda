import { isDevMode } from '@angular/core';
import { translocoConfig } from '@jsverse/transloco';

/** Langues supportées par l'application (source unique de vérité). */
export const AVAILABLE_LANGS = ['en', 'fr'] as const;

/** Union des codes de langue supportés : `'en' | 'fr'`. */
export type Lang = (typeof AVAILABLE_LANGS)[number];

/** Langue utilisée quand ni l'URL ni le navigateur n'en fournissent une supportée. */
export const DEFAULT_LANG: Lang = 'en';

/** Vérifie qu'une valeur (segment d'URL, langue du navigateur…) est une langue supportée. */
export function isLang(value: unknown): value is Lang {
  return typeof value === 'string' && (AVAILABLE_LANGS as readonly string[]).includes(value);
}

/** Configuration Transloco partagée par l'application et les tests. */
export const appTranslocoConfig = translocoConfig({
  availableLangs: [...AVAILABLE_LANGS],
  defaultLang: DEFAULT_LANG,
  // Met à jour les vues quand la langue change à l'exécution.
  reRenderOnLangChange: true,
  // Masque les avertissements de clés manquantes en production.
  prodMode: !isDevMode(),
});
