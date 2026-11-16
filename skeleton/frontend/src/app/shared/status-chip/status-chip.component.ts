import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { onColor } from '../../core/pure/contrast';

/**
 * Status label on a coloured chip. The label text is always shown. With a valid hex colour the
 * background is that colour and the text is black or white by contrast; otherwise the
 * Primary_Override styles of `.sk-status-chip` apply.
 */
@Component({
  selector: 'sk-status-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'sk-status-chip',
    '[style.background-color]': 'bg()',
    '[style.color]': 'fg()',
  },
  template: `{{ label() }}`,
  styles: `
    :host {
      display: inline-block;
      padding: 0 8px;
      border-radius: 4px;
      font-size: 12px;
      line-height: 20px;
      white-space: nowrap;
    }
  `,
})
export class StatusChipComponent {
  readonly label = input.required<string>();
  readonly color = input<string | null | undefined>(null);

  private readonly valid = computed(() => {
    const c = this.color();
    if (!c) {
      return null;
    }
    try {
      return onColor(c) ? c : null;
    } catch {
      return null;
    }
  });

  protected readonly bg = computed(() => this.valid());
  protected readonly fg = computed(() => {
    const c = this.valid();
    return c ? onColor(c) : null;
  });
}
