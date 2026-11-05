import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, PRIMARY_OUTLET, Router, RouterLink } from '@angular/router';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { filter, map } from 'rxjs';

import { AVAILABLE_LANGS } from '../i18n/i18n.config';

@Component({
  selector: 'app-language-switcher',
  imports: [RouterLink, TranslocoDirective],
  templateUrl: './language-switcher.html',
  styleUrl: './language-switcher.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LanguageSwitcher {
  private readonly router = inject(Router);

  protected readonly activeLang = inject(TranslocoService).activeLang;

  /** URL courante, mise à jour à chaque navigation. */
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );

  /**
   * Un lien par langue : la même page, avec le premier segment de l'URL remplacé.
   * `/fr/about?x=1` donne `/en/about?x=1` et `/fr/about?x=1`.
   */
  protected readonly links = computed(() => {
    const current = this.router.parseUrl(this.url());
    const [, ...pageSegments] = current.root.children[PRIMARY_OUTLET]?.segments ?? [];

    return AVAILABLE_LANGS.map((lang) => ({
      lang,
      url: this.router.createUrlTree(['/', lang, ...pageSegments.map(({ path }) => path)], {
        queryParams: current.queryParams,
        fragment: current.fragment ?? undefined,
      }),
    }));
  });
}
