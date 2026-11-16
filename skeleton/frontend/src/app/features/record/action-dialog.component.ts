import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzInputModule } from 'ng-zorro-antd/input';
import type { ActionInfo, Violation } from '../../core/api/api.types';
import { actionDialogReady } from '../../core/pure/action-helpers';

export interface ActionDialogResult {
  remarks?: string;
  attachment?: string;
}

/**
 * In-page dialog panel for an action that takes remarks and/or an attachment reference.
 * The parent renders it while open and removes it on `cancelled` or after a successful action.
 */
@Component({
  selector: 'sk-action-dialog',
  imports: [NzButtonModule, NzInputModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="sk-action-dialog" role="dialog" aria-modal="true" aria-labelledby="sk-ad-title">
      <h3 id="sk-ad-title">{{ action().label }}</h3>
      @if (action().confirm) {
        <p class="sk-ad-confirm">{{ action().confirm }}</p>
      }
      @if (action().remarks !== 'hidden') {
        <div class="sk-ad-field">
          <label for="sk-ad-remarks">Remarks{{ action().remarks === 'required' ? ' (required)' : '' }}</label>
          <textarea
            id="sk-ad-remarks"
            nz-input
            rows="3"
            [value]="remarks()"
            [attr.aria-required]="action().remarks === 'required'"
            [attr.aria-invalid]="remarksErrors().length > 0"
            [attr.aria-describedby]="remarksErrors().length > 0 ? 'sk-ad-remarks-error' : null"
            (input)="remarks.set($any($event.target).value)"
          ></textarea>
          @if (remarksErrors().length > 0) {
            <div id="sk-ad-remarks-error" class="sk-ad-error" role="alert">{{ remarksErrors().join(' ') }}</div>
          }
        </div>
      }
      @if (action().attachment !== 'hidden') {
        <div class="sk-ad-field">
          <label for="sk-ad-attachment"
            >Attachment reference{{ action().attachment === 'required' ? ' (required)' : '' }}</label
          >
          <input
            id="sk-ad-attachment"
            nz-input
            type="text"
            [value]="attachment()"
            [attr.aria-required]="action().attachment === 'required'"
            [attr.aria-invalid]="attachmentErrors().length > 0"
            [attr.aria-describedby]="attachmentErrors().length > 0 ? 'sk-ad-attachment-error' : null"
            (input)="attachment.set($any($event.target).value)"
          />
          @if (attachmentErrors().length > 0) {
            <div id="sk-ad-attachment-error" class="sk-ad-error" role="alert">
              {{ attachmentErrors().join(' ') }}
            </div>
          }
        </div>
      }
      <div class="sk-ad-buttons">
        <button type="button" nz-button class="sk-ad-cancel" [disabled]="pending()" (click)="cancelled.emit()">
          Cancel
        </button>
        <button
          type="button"
          nz-button
          class="sk-ad-confirm-btn"
          [nzType]="action().style === 'primary' ? 'primary' : 'default'"
          [nzDanger]="action().style === 'danger'"
          [nzLoading]="pending()"
          [disabled]="!ready() || pending()"
          (click)="confirm()"
        >
          Confirm
        </button>
      </div>
    </div>
  `,
})
export class ActionDialogComponent {
  readonly action = input.required<ActionInfo>();
  readonly pending = input<boolean>(false);
  readonly violations = input<Violation[]>([]);

  readonly confirmed = output<ActionDialogResult>();
  readonly cancelled = output<void>();

  protected readonly remarks = signal('');
  protected readonly attachment = signal('');

  protected readonly ready = computed(() => actionDialogReady(this.action(), this.remarks(), this.attachment()));
  protected readonly remarksErrors = computed(() => this.messagesFor('remarks'));
  protected readonly attachmentErrors = computed(() => this.messagesFor('attachment'));

  private messagesFor(field: string): string[] {
    return this.violations()
      .filter((v) => v.field === field)
      .map((v) => v.message || v.rule);
  }

  protected confirm(): void {
    if (!this.ready() || this.pending()) return;
    const result: ActionDialogResult = {};
    if (this.remarks().trim() !== '') result.remarks = this.remarks();
    if (this.attachment().trim() !== '') result.attachment = this.attachment();
    this.confirmed.emit(result);
  }
}
