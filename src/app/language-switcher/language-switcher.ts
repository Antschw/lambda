import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DOCUMENT,
  inject,
  LOCALE_ID,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';

import { LOCALES } from '../i18n/locales';

@Component({
  selector: 'app-language-switcher',
  templateUrl: './language-switcher.html',
  styleUrl: './language-switcher.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LanguageSwitcher {
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);

  /** Langue du build en cours d'exécution ('en' ou 'fr'), fixée à la compilation. */
  protected readonly currentLocale = inject(LOCALE_ID);

  /** URL courante dans l'application (sans le préfixe /fr/), mise à jour à chaque navigation. */
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );

  /**
   * Un lien par langue vers la même page dans l'autre build.
   * Depuis /fr/about : `../en/about` résolu par rapport à <base href="/fr/"> donne /en/about.
   * Ça fonctionne aussi si le site est déployé dans un sous-dossier (/mon-app/fr/).
   */
  protected readonly links = computed(() =>
    LOCALES.map((locale) => ({
      ...locale,
      href: new URL(`../${locale.code}${this.url()}`, this.document.baseURI).href,
    })),
  );
}
