import { Component, DOCUMENT, effect, inject } from '@angular/core';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';

import { LanguageSwitcher } from './language-switcher/language-switcher';

@Component({
  selector: 'app-root',
  imports: [TranslocoDirective, LanguageSwitcher],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly document = inject(DOCUMENT);
  private readonly activeLang = inject(TranslocoService).activeLang;

  protected readonly name = 'Angular';

  constructor() {
    // Garde <html lang="..."> synchronisé avec la langue active (accessibilité, SEO).
    effect(() => {
      this.document.documentElement.lang = this.activeLang();
    });
  }
}
