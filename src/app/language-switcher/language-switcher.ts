import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';

import { AVAILABLE_LANGS, Lang } from '../i18n/i18n.config';

@Component({
  selector: 'app-language-switcher',
  imports: [TranslocoDirective],
  templateUrl: './language-switcher.html',
  styleUrl: './language-switcher.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LanguageSwitcher {
  private readonly transloco = inject(TranslocoService);

  protected readonly langs = AVAILABLE_LANGS;
  /** Signal exposé par Transloco : la vue se met à jour à chaque changement de langue. */
  protected readonly activeLang = this.transloco.activeLang;

  protected changeLang(lang: Lang): void {
    this.transloco.setActiveLang(lang);
  }
}
