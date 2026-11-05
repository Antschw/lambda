import { ChangeDetectionStrategy, Component, DOCUMENT, effect, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';

import { LanguageSwitcher } from './language-switcher/language-switcher';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TranslocoDirective, LanguageSwitcher],
  templateUrl: './app.html',
  styleUrl: './app.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  private readonly document = inject(DOCUMENT);

  /** Langue active, mise à jour par le resolver de la route `:lang`. */
  protected readonly activeLang = inject(TranslocoService).activeLang;

  constructor() {
    // Garde <html lang="..."> synchronisé avec la langue active (accessibilité, SEO).
    effect(() => {
      this.document.documentElement.lang = this.activeLang();
    });
  }
}
