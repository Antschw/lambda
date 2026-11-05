import { inject } from '@angular/core';
import { CanMatchFn, RedirectFunction, ResolveFn } from '@angular/router';
import { getBrowserLang, TranslocoService } from '@jsverse/transloco';
import { map, tap } from 'rxjs';

import { DEFAULT_LANG, isLang, Lang } from './i18n.config';

/**
 * La route `:lang` ne correspond qu'aux langues supportées.
 * Pour `/de/...`, elle est ignorée et la route `**` prend le relais.
 */
export const supportedLangGuard: CanMatchFn = (_route, segments) => isLang(segments[0]?.path);

/**
 * L'URL pilote la langue : on charge les traductions de la langue demandée,
 * puis on l'active. La page ne s'affiche qu'une fois les traductions disponibles.
 */
export const langResolver: ResolveFn<Lang> = (route) => {
  const param = route.paramMap.get('lang');
  const lang = isLang(param) ? param : DEFAULT_LANG;
  const transloco = inject(TranslocoService);

  return transloco.load(lang).pipe(
    tap(() => transloco.setActiveLang(lang)),
    map(() => lang),
  );
};

/** Sans langue (ou avec une langue inconnue) dans l'URL : langue du navigateur si supportée. */
export const redirectToPreferredLang: RedirectFunction = () => {
  const browserLang = getBrowserLang();
  return `/${isLang(browserLang) ? browserLang : DEFAULT_LANG}`;
};
