import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';

import { App } from './app';
import { getTranslocoModule } from './i18n/transloco-testing';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App, getTranslocoModule()],
    }).compileComponents();
  });

  it('should render the translations in English by default', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;

    expect(compiled.querySelector('h1')?.textContent).toBe('Transloco demo');
    expect(compiled.querySelector('main p')?.textContent).toBe('Hello Angular!');
  });

  it('should re-render the translations when the language changes', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    TestBed.inject(TranslocoService).setActiveLang('fr');
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;

    expect(compiled.querySelector('h1')?.textContent).toBe('Démo Transloco');
    expect(compiled.querySelector('main p')?.textContent).toBe('Bonjour Angular !');
    expect(document.documentElement.lang).toBe('fr');
  });
});
