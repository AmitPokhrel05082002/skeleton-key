import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import type { ActionInfo, Violation } from '../../core/api/api.types';
import { ActionDialogComponent, type ActionDialogResult } from './action-dialog.component';

const base: ActionInfo = { code: 'APPROVE', label: 'Approve', style: 'primary', remarks: 'required', attachment: 'optional' };

function setup(action: ActionInfo, violations: Violation[] = [], pending = false) {
  const fixture = TestBed.createComponent(ActionDialogComponent);
  fixture.componentRef.setInput('action', action);
  fixture.componentRef.setInput('violations', violations);
  fixture.componentRef.setInput('pending', pending);
  const confirmed: ActionDialogResult[] = [];
  let cancelled = 0;
  fixture.componentInstance.confirmed.subscribe((r) => confirmed.push(r));
  fixture.componentInstance.cancelled.subscribe(() => cancelled++);
  fixture.detectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const type = (sel: string, value: string) => {
    const input = el.querySelector(sel) as HTMLInputElement | HTMLTextAreaElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };
  const confirmBtn = () => el.querySelector('.sk-ad-confirm-btn') as HTMLButtonElement;
  return { fixture, el, confirmed, cancelledCount: () => cancelled, type, confirmBtn };
}

describe('ActionDialogComponent', () => {
  it('disables confirm until required remarks are non-blank, then emits trimmed-presence values', () => {
    const { el, confirmed, type, confirmBtn } = setup({ ...base, confirm: 'Really approve?' });
    expect(el.textContent).toContain('Really approve?');
    expect(confirmBtn().disabled).toBe(true);
    type('#sk-ad-remarks', '   ');
    expect(confirmBtn().disabled).toBe(true);
    type('#sk-ad-remarks', 'ok');
    expect(confirmBtn().disabled).toBe(false);
    confirmBtn().click();
    expect(confirmed).toEqual([{ remarks: 'ok' }]);
  });

  it('requires the attachment reference when required and omits hidden inputs', () => {
    const { el, type, confirmBtn } = setup({ ...base, remarks: 'hidden', attachment: 'required' });
    expect(el.querySelector('#sk-ad-remarks')).toBeNull();
    expect(el.querySelector('label[for="sk-ad-attachment"]')?.textContent).toContain('Attachment reference');
    expect(confirmBtn().disabled).toBe(true);
    type('#sk-ad-attachment', 'DOC-1');
    expect(confirmBtn().disabled).toBe(false);
  });

  it('maps remarks and attachment violations onto the inputs', () => {
    const { el } = setup({ ...base, attachment: 'optional' }, [
      { field: 'remarks', rule: 'required', message: 'Remarks needed' },
      { field: 'attachment', rule: 'required' },
      { field: 'other', rule: 'x', message: 'ignored' },
    ]);
    expect(el.querySelector('#sk-ad-remarks-error')?.textContent).toContain('Remarks needed');
    expect(el.querySelector('#sk-ad-attachment-error')?.textContent).toContain('required');
    expect(el.textContent).not.toContain('ignored');
    expect(el.querySelector('#sk-ad-remarks')?.getAttribute('aria-invalid')).toBe('true');
  });

  it('disables confirm and cancel while pending', () => {
    const { confirmBtn, el } = setup({ ...base, remarks: 'optional' }, [], true);
    expect(confirmBtn().disabled).toBe(true);
    expect((el.querySelector('.sk-ad-cancel') as HTMLButtonElement).disabled).toBe(true);
  });

  it('emits cancelled when not pending', () => {
    const { el, cancelledCount } = setup({ ...base, remarks: 'optional' });
    (el.querySelector('.sk-ad-cancel') as HTMLButtonElement).click();
    expect(cancelledCount()).toBe(1);
  });
});
