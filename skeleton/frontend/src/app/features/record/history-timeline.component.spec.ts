import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import type { HistoryEntry } from '../../core/api/api.types';
import { HistoryTimelineComponent } from './history-timeline.component';

function render(entries: HistoryEntry[]): HTMLElement {
  const fixture = TestBed.createComponent(HistoryTimelineComponent);
  fixture.componentRef.setInput('entries', entries);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('HistoryTimelineComponent', () => {
  it('renders entries in backend order with all fields', () => {
    const el = render([
      {
        kind: 'ACTION',
        actionCode: 'APPROVE',
        fromStep: 'REVIEW',
        toStep: 'DONE',
        fromStatus: 'Open',
        toStatus: 'Closed',
        actorName: 'Ana',
        actorId: 'u1',
        remarks: 'fine',
        attachmentRef: 'DOC-9',
        detail: { a: 1 },
        eventTime: '2026-01-01T10:00:00Z',
        timeInStepMs: 2 * 3600_000 + 5 * 60_000,
      },
      { kind: 'START', actorId: 'u2', eventTime: '2026-01-01T09:00:00Z', timeInStepMs: 0 },
    ]);
    const items = el.querySelectorAll('.sk-history-entry');
    expect(items.length).toBe(2);
    const first = items[0].textContent ?? '';
    for (const s of ['ACTION', 'APPROVE', 'REVIEW', 'DONE', 'Open', 'Closed', 'Ana', 'fine', 'DOC-9', '{"a":1}', '2h 5m', '2026-01-01T10:00:00Z']) {
      expect(first).toContain(s);
    }
    expect(items[1].textContent).toContain('u2');
    expect(items[1].querySelector('.sk-h-remarks')).toBeNull();
    expect(items[1].querySelector('.sk-h-attachment')).toBeNull();
    expect(items[1].querySelector('.sk-h-detail')).toBeNull();
  });

  it('renders a string detail as-is and markup as text', () => {
    const el = render([
      { kind: 'NOTE', detail: '<b>x</b>', remarks: '<i>y</i>', eventTime: 't', timeInStepMs: 1000 },
    ]);
    expect(el.querySelector('.sk-h-detail')?.textContent).toContain('<b>x</b>');
    expect(el.querySelector('b')).toBeNull();
    expect(el.querySelector('i')).toBeNull();
  });

  it('falls back to a dash when actor is unknown', () => {
    const el = render([{ kind: 'SLA', eventTime: 't', timeInStepMs: 0 }]);
    expect(el.querySelector('.sk-h-actor')?.textContent).toContain('By -');
  });
});
