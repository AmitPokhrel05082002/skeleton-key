import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { NzTimelineModule } from 'ng-zorro-antd/timeline';
import type { HistoryEntry } from '../../core/api/api.types';
import { displayName } from '../../core/pure/display-name';
import { formatDuration } from '../../core/pure/duration';

/** Renders the instance history in backend order. All text is interpolated, never innerHTML. */
@Component({
  selector: 'sk-history-timeline',
  imports: [NzTimelineModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-timeline>
      @for (e of entries(); track $index) {
        <nz-timeline-item>
          <div class="sk-history-entry">
            <div>
              <strong class="sk-h-kind">{{ e.kind }}</strong>
              @if (e.actionCode) {
                <span class="sk-h-action"> {{ e.actionCode }}</span>
              }
              <span class="sk-h-time"> {{ e.eventTime }}</span>
            </div>
            <div class="sk-h-actor">By {{ actor(e) }}</div>
            @if (e.fromStep || e.toStep) {
              <div class="sk-h-steps">Step: {{ e.fromStep || '-' }} → {{ e.toStep || '-' }}</div>
            }
            @if (e.fromStatus || e.toStatus) {
              <div class="sk-h-status">Status: {{ e.fromStatus || '-' }} → {{ e.toStatus || '-' }}</div>
            }
            <div class="sk-h-duration">Time in step: {{ duration(e) }}</div>
            @if (e.remarks) {
              <div class="sk-h-remarks">Remarks: {{ e.remarks }}</div>
            }
            @if (e.attachmentRef) {
              <div class="sk-h-attachment">Attachment: {{ e.attachmentRef }}</div>
            }
            @if (hasDetail(e)) {
              <div class="sk-h-detail">Detail: {{ detailText(e) }}</div>
            }
          </div>
        </nz-timeline-item>
      }
    </nz-timeline>
  `,
})
export class HistoryTimelineComponent {
  readonly entries = input<HistoryEntry[]>([]);

  protected actor(e: HistoryEntry): string {
    return displayName(e.actorName, e.actorId);
  }

  protected duration(e: HistoryEntry): string {
    return formatDuration(e.timeInStepMs);
  }

  protected hasDetail(e: HistoryEntry): boolean {
    return e.detail !== undefined && e.detail !== null && e.detail !== '';
  }

  protected detailText(e: HistoryEntry): string {
    return typeof e.detail === 'string' ? e.detail : (JSON.stringify(e.detail) ?? '');
  }
}
