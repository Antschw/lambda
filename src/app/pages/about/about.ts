import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';

@Component({
  selector: 'app-about',
  imports: [TranslocoDirective],
  templateUrl: './about.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class About {}
