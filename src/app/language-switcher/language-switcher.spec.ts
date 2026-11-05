import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';

import { getTranslocoModule } from '../i18n/transloco-testing';
import { LanguageSwitcher } from './language-switcher';

describe('LanguageSwitcher', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LanguageSwitcher, getTranslocoModule()],
    }).compileComponents();
  });

  it('should render one button per available language', async () => {
    const fixture = TestBed.createComponent(LanguageSwitcher);
    await fixture.whenStable();
    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('button');

    expect(Array.from(buttons, (button) => button.textContent?.trim())).toEqual(['en', 'fr']);
  });

  it('should switch the active language on click', async () => {
    const fixture = TestBed.createComponent(LanguageSwitcher);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;

    compiled.querySelector<HTMLButtonElement>('button:last-of-type')?.click();
    await fixture.whenStable();

    expect(TestBed.inject(TranslocoService).getActiveLang()).toBe('fr');
    expect(compiled.querySelector('[aria-pressed="true"]')?.textContent?.trim()).toBe('fr');
    expect(compiled.querySelector('nav')?.getAttribute('aria-label')).toBe('Langue');
  });
});
