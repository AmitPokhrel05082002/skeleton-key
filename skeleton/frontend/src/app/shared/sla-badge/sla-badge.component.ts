import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { SlaState } from '../../core/pure/sla';

/** Icon plus text for DUE_SOON and OVERDUE. Renders nothing for NONE. */
@Component({
  selector: 'sk-sla-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (text(); as t) {
      <span class="sla-badge" [class.sla-overdue]="state() === 'OVERDUE'">
        <span class="sla-icon" aria-hidden="true">{{ state() === 'OVERDUE' ? '⚠' : '⏱' }}</span>
        <span>{{ t }}</span>
      </span>
    }
  `,
  styles: `
    .sla-badge {
      display: inline-flex;
      gap: 4px;
      align-items: center;
      font-size: 12px;
      font-weight: 600;
      color: #ad4e00;
    }
    .sla-overdue {
      color: #a8071a;
    }
  `,
})
export class SlaBadgeComponent {
  readonly state = input.required<SlaState>();

  protected readonly text = computed(() => {
    switch (this.state()) {
      case 'OVERDUE':
        return 'Overdue';
      case 'DUE_SOON':
        return 'Due soon';
      default:
        return '';
    }
  });
}
