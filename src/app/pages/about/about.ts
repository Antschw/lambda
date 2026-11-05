import { ChangeDetectionStrategy, Component } from '@angular/core';

import { LOCALES } from '../../i18n/locales';

@Component({
  selector: 'app-about',
  templateUrl: './about.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class About {
  protected readonly localeCount = LOCALES.length;
}
