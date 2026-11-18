import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api/api.service';
import type { ApiError, PublicSubset, TrackResponse } from '../../core/api/api.types';
import { ThemeService } from '../../core/theme/theme.service';
import { TRACK_NOT_FOUND, TRACK_RATE_LIMITED, TrackPage } from './track.page';

const subset: PublicSubset = { app: { code: 'GRV', name: 'Grievance' }, forms: [] };

const response: TrackResponse = {
  reference: 'GRV-2026-000123',
  title: 'Broken street light',
  status: { label: 'Under review', color: '#f59e0b', category: 'IN_PROGRESS' },
  createdAt: '2026-11-01T08:00:00.000Z',
  updatedAt: '2026-11-01T10:00:00.000Z',
  timeline: [
    { at: '2026-11-01T08:00:00.000Z', status: 'Received' },
    { at: '2026-11-01T10:00:00.000Z', status: 'Under review' },
  ],
};

function setup(track: unknown, query: Record<string, string> = {}) {
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiService, useValue: { publicApp: () => of(subset), track } },
      { provide: ThemeService, useValue: { apply: vi.fn(), applyBranding: vi.fn() } },
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: { paramMap: convertToParamMap({ app: 'GRIEVANCE' }), queryParamMap: convertToParamMap(query) },
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(TrackPage);
  fixture.detectChanges();
  return fixture;
}

const el = (f: ComponentFixture<TrackPage>) => f.nativeElement as HTMLElement;
const input = (f: ComponentFixture<TrackPage>, id: string) => el(f).querySelector(`#${id}`) as HTMLInputElement;
const button = (f: ComponentFixture<TrackPage>) => el(f).querySelector('button.sk-track-submit') as HTMLButtonElement;
const submit = (f: ComponentFixture<TrackPage>) => {
  el(f).querySelector('form')!.dispatchEvent(new Event('submit'));
  f.detectChanges();
};

async function fill(f: ComponentFixture<TrackPage>, reference: string, email: string) {
  // Let NgModel finish its initial (asynchronous) write before typing.
  await f.whenStable();
  for (const [id, value] of [['sk-track-reference', reference], ['sk-track-email', email]] as const) {
    const i = input(f, id);
    i.value = value;
    i.dispatchEvent(new Event('input'));
  }
  f.detectChanges();
  await f.whenStable();
  f.detectChanges();
}

describe('TrackPage', () => {
  beforeEach(() => {
    vi.spyOn(Storage.prototype, 'setItem');
  });
  afterEach(() => vi.restoreAllMocks());

  it('prefills only the reference from the URL, never the email', async () => {
    const f = setup(vi.fn(), { reference: 'GRV-2026-000123', email: 'leak@example.org' });
    await f.whenStable();
    f.detectChanges();
    expect(input(f, 'sk-track-reference').value).toBe('GRV-2026-000123');
    expect(input(f, 'sk-track-email').value).toBe('');
  });

  it('keeps the button disabled until both fields are valid', async () => {
    const f = setup(vi.fn());
    expect(button(f).disabled).toBe(true);
    await fill(f, 'GRV-1', 'not-an-email');
    expect(button(f).disabled).toBe(true);
    await fill(f, 'GRV-1', 'a@b.org');
    expect(button(f).disabled).toBe(false);
  });

  it('shows the result card with status chip and timeline, and stores nothing', async () => {
    const track = vi.fn(() => of(response));
    const f = setup(track);
    await fill(f, ' GRV-2026-000123 ', ' a@b.org ');
    submit(f);
    expect(track).toHaveBeenCalledWith('GRIEVANCE', { reference: 'GRV-2026-000123', email: 'a@b.org' });
    const text = el(f).textContent ?? '';
    expect(text).toContain('Broken street light');
    expect(text).toContain('Under review');
    const items = el(f).querySelectorAll('.sk-track-status-name');
    expect(Array.from(items).map((i) => i.textContent?.trim())).toEqual(['Received', 'Under review']);
    expect(Storage.prototype.setItem).not.toHaveBeenCalled();
    expect(document.cookie).not.toContain('a@b.org');
  });

  it('shows one message for any 404 that does not say which field was wrong', async () => {
    const err: ApiError = { status: 404, code: 'NOT_FOUND', message: 'No matching request found', violations: [] };
    const f = setup(() => throwError(() => err));
    await fill(f, 'GRV-1', 'a@b.org');
    submit(f);
    expect(el(f).textContent).toContain(TRACK_NOT_FOUND);
    expect(el(f).querySelector('.sk-track-result')).toBeNull();
  });

  it('disables the button for Retry-After seconds on 429', async () => {
    vi.useFakeTimers();
    const err = { status: 429, code: 'RATE_LIMITED', message: 'x', violations: [], retryAfterSeconds: 3 } as ApiError;
    const f = setup(() => throwError(() => err));
    await fill(f, 'GRV-1', 'a@b.org');
    submit(f);
    expect(el(f).textContent).toContain(TRACK_RATE_LIMITED);
    expect(button(f).disabled).toBe(true);
    vi.advanceTimersByTime(3000);
    f.detectChanges();
    expect(button(f).disabled).toBe(false);
    vi.useRealTimers();
  });
});
