import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import type { ApiError } from '../../core/api/api.types';
import { errorText } from '../../core/pure/error-text';

/**
 * Shows an ApiError. For status >= 500 only generic text plus the requestId is shown.
 * Otherwise `unmatched` messages are listed when given, else the error message.
 * Status 0 (no response) offers a Retry output.
 */
@Component({
  selector: 'sk-error-alert',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NzAlertModule, NzButtonModule],
  template: `
    @if (error(); as e) {
      <nz-alert
        nzType="error"
        nzShowIcon
        [nzMessage]="messageTpl"
        [nzDescription]="e.status === 0 ? retryTpl : null"
        role="alert"
      />
      <ng-template #messageTpl>
        @for (m of messages(); track $index) {
          <div class="error-line">{{ m }}</div>
        }
      </ng-template>
      <ng-template #retryTpl>
        <button nz-button nzSize="small" type="button" (click)="retry.emit()">Retry</button>
      </ng-template>
    }
  `,
})
export class ErrorAlertComponent {
  readonly error = input<ApiError | null | undefined>(null);
  /** Violation messages that matched no form control. */
  readonly unmatched = input<readonly string[]>([]);
  readonly retry = output<void>();

  protected readonly messages = computed<string[]>(() => {
    const e = this.error();
    if (!e) {
      return [];
    }
    const u = this.unmatched();
    if (e.status < 500 && u.length > 0) {
      return [...u];
    }
    return [errorText(e)];
  });
}
