import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { App } from './app';
import { routes } from './app.routes';
import { getTranslocoModule } from './i18n/transloco-testing';

describe('App', () => {
  let router: Router;

  async function render(url: string): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(App);
    await router.navigateByUrl(url);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App, getTranslocoModule()],
      providers: [provideRouter(routes)],
    }).compileComponents();
    router = TestBed.inject(Router);
  });

  it('should redirect / to the browser language', async () => {
    const page = await render('/');

    // La langue de jsdom est en-US.
    expect(router.url).toBe('/en');
    expect(page.querySelector('h1')?.textContent).toBe('Transloco demo');
  });

  it('should take the language from the URL', async () => {
    const page = await render('/fr/about');

    expect(page.querySelector('h1')?.textContent).toBe('À propos');
    expect(page.querySelector('header nav a')?.getAttribute('href')).toBe('/fr');
    expect(document.documentElement.lang).toBe('fr');
  });

  it('should redirect an unsupported language to the preferred one', async () => {
    await render('/de/about');

    expect(router.url).toBe('/en');
  });

  it('should redirect an unknown page to the home page of the same language', async () => {
    await render('/fr/unknown');

    expect(router.url).toBe('/fr');
  });
});
