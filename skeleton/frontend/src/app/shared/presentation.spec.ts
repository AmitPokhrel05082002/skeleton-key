import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import type { ApiError } from '../core/api/api.types';
import { EmptyStateComponent } from './empty-state/empty-state.component';
import { ErrorAlertComponent } from './error-alert/error-alert.component';
import { SlaBadgeComponent } from './sla-badge/sla-badge.component';
import { StatusChipComponent } from './status-chip/status-chip.component';

const apiError = (over: Partial<ApiError>): ApiError => ({
  status: 400,
  code: 'X',
  message: 'Bad thing',
  violations: [],
  ...over,
});

describe('StatusChipComponent', () => {
  it('shows the label with the sk-status-chip class and a contrasting text colour', async () => {
    const f = TestBed.createComponent(StatusChipComponent);
    f.componentRef.setInput('label', 'Open');
    f.componentRef.setInput('color', '#ffffff');
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Open');
    expect(el.classList.contains('sk-status-chip')).toBe(true);
    expect(el.style.color).toBe('rgb(0, 0, 0)');
  });

  it('shows the label when the colour is missing or invalid', async () => {
    const f = TestBed.createComponent(StatusChipComponent);
    f.componentRef.setInput('label', 'Done');
    f.componentRef.setInput('color', 'nope');
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Done');
    expect(el.style.color).toBe('');
  });
});

describe('SlaBadgeComponent', () => {
  const render = async (state: 'NONE' | 'DUE_SOON' | 'OVERDUE'): Promise<HTMLElement> => {
    const f = TestBed.createComponent(SlaBadgeComponent);
    f.componentRef.setInput('state', state);
    await f.whenStable();
    return f.nativeElement as HTMLElement;
  };

  it('renders nothing for NONE', async () => {
    expect((await render('NONE')).textContent?.trim()).toBe('');
  });

  it('renders text and an icon for OVERDUE and DUE_SOON', async () => {
    const overdue = await render('OVERDUE');
    expect(overdue.textContent).toContain('Overdue');
    expect(overdue.querySelector('.sla-icon')?.getAttribute('aria-hidden')).toBe('true');
    expect((await render('DUE_SOON')).textContent).toContain('Due soon');
  });
});

describe('ErrorAlertComponent', () => {
  const render = async (error: ApiError, unmatched: string[] = []) => {
    const f = TestBed.createComponent(ErrorAlertComponent);
    f.componentRef.setInput('error', error);
    f.componentRef.setInput('unmatched', unmatched);
    await f.whenStable();
    return f;
  };

  it('shows the message for a 4xx error', async () => {
    const f = await render(apiError({}));
    expect((f.nativeElement as HTMLElement).textContent).toContain('Bad thing');
  });

  it('shows generic text and requestId, never the server message, for 5xx', async () => {
    const f = await render(apiError({ status: 500, message: 'NullPointer at X', requestId: 'req-1' }));
    const text = (f.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('req-1');
    expect(text).not.toContain('NullPointer');
  });

  it('shows unmatched messages when given', async () => {
    const f = await render(apiError({}), ['Name is required']);
    const text = (f.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Name is required');
    expect(text).not.toContain('Bad thing');
  });

  it('offers Retry for status 0 and emits', async () => {
    const f = await render(apiError({ status: 0, code: 'NETWORK', message: 'offline' }));
    let count = 0;
    f.componentInstance.retry.subscribe(() => count++);
    const btn = (f.nativeElement as HTMLElement).querySelector('button');
    expect(btn?.textContent).toContain('Retry');
    btn?.click();
    expect(count).toBe(1);
  });

  it('has no Retry for other statuses', async () => {
    const f = await render(apiError({}));
    expect((f.nativeElement as HTMLElement).querySelector('button')).toBeNull();
  });
});

describe('EmptyStateComponent', () => {
  it('shows the message', async () => {
    const f = TestBed.createComponent(EmptyStateComponent);
    f.componentRef.setInput('message', 'No records');
    await f.whenStable();
    expect((f.nativeElement as HTMLElement).textContent).toContain('No records');
  });
});
