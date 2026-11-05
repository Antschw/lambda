/**
 * Langues dans lesquelles l'application est construite.
 * Doit refléter la section `i18n` d'angular.json (vérifié par un test).
 *
 * Le libellé est le nom de la langue dans cette langue : il ne se traduit pas.
 */
export const LOCALES = [
  { code: 'en', label: 'English' },
  { code: 'fr', label: 'Français' },
] as const;

/** Union des codes de langue : `'en' | 'fr'`. */
export type Locale = (typeof LOCALES)[number]['code'];
