import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { App } from '../app';
import { routes } from '../app.routes';

describe('LanguageSwitcher', () => {
  async function render(url: string): Promise<HTMLAnchorElement[]> {
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl(url);
    await fixture.whenStable();
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('app-language-switcher a'),
    );
  }

  beforeAll(() => {
    // Le build `fr` embarque ces données (dates, nombres) ; dans un test, on les enregistre.
    registerLocaleData(localeFr);
  });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      // Simule le build français.
      providers: [provideRouter(routes), { provide: LOCALE_ID, useValue: 'fr' }],
    }).compileComponents();
  });

  it('should link to the current page in every language', async () => {
    const links = await render('/about');

    expect(links.map((link) => new URL(link.href).pathname)).toEqual(['/en/about', '/fr/about']);
    expect(links.map((link) => link.textContent?.trim())).toEqual(['English', 'Français']);
  });

  it('should mark the language of the current build', async () => {
    const links = await render('/');
    const current = links.find((link) => link.getAttribute('aria-current') === 'true');

    expect(current?.lang).toBe('fr');
    expect(current?.hreflang).toBe('fr');
  });
});
