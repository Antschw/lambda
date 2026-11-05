import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { App } from './app';
import { routes } from './app.routes';

// Les tests s'exécutent dans la langue source (anglais), sans traduction chargée.
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
      imports: [App],
      providers: [provideRouter(routes)],
    }).compileComponents();
    router = TestBed.inject(Router);
  });

  it('should render the home page', async () => {
    const page = await render('/');

    expect(page.querySelector('h1')?.textContent).toBe('Angular i18n demo');
    expect(page.querySelector('main p')?.textContent).toBe('Hello Angular!');
    expect(document.title).toBe('Home');
  });

  it('should render the plural ICU message on the about page', async () => {
    const page = await render('/about');

    expect(page.querySelector('h1')?.textContent).toBe('About');
    expect(page.textContent).toContain('2 languages available.');
    expect(document.title).toBe('About');
  });

  it('should redirect an unknown page to the home page', async () => {
    await render('/unknown');

    expect(router.url).toBe('/');
  });
});
