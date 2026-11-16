import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { NzEmptyModule } from 'ng-zorro-antd/empty';

/** Plain empty state with a message. */
@Component({
  selector: 'sk-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NzEmptyModule],
  template: `<nz-empty [nzNotFoundContent]="message()" />`,
})
export class EmptyStateComponent {
  readonly message = input<string>('Nothing to show');
}
