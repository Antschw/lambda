import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';

import { App } from '../app';
import { routes } from '../app.routes';
import { getTranslocoModule } from '../i18n/transloco-testing';

describe('LanguageSwitcher', () => {
  let fixture: ComponentFixture<App>;

  async function render(url: string): Promise<HTMLElement> {
    fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl(url);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  function switcherLinks(page: HTMLElement): HTMLAnchorElement[] {
    return Array.from(page.querySelectorAll('app-language-switcher a'));
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App, getTranslocoModule()],
      providers: [provideRouter(routes)],
    }).compileComponents();
  });

  it('should link to the current page in every language', async () => {
    const page = await render('/fr/about?tab=1');

    expect(switcherLinks(page).map((link) => link.getAttribute('href'))).toEqual([
      '/en/about?tab=1',
      '/fr/about?tab=1',
    ]);
    expect(page.querySelector('[aria-current="true"]')?.textContent?.trim()).toBe('fr');
  });

  it('should change the URL and the language on click', async () => {
    const page = await render('/fr/about');

    switcherLinks(page)[0].click();
    // whenStable() attend aussi la fin de la navigation déclenchée par le clic.
    await fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/en/about');
    expect(TestBed.inject(TranslocoService).getActiveLang()).toBe('en');
    expect(page.querySelector('h1')?.textContent).toBe('About');
  });
});
