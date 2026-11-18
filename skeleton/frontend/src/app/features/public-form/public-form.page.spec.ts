import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api/api.service';
import type { ApiError, PublicSubset, SubmitResult } from '../../core/api/api.types';
import { PublicFormPage } from './public-form.page';

const subset: PublicSubset = {
  app: { code: 'GRV', name: 'Grievance' },
  forms: [
    {
      code: 'REPORT',
      label: 'Report',
      entity: 'GRIEVANCE',
      public: true,
      sections: [{ fields: ['title', 'detail'] }],
    },
  ],
  entities: [
    {
      code: 'GRIEVANCE',
      fields: [
        { code: 'title', label: 'Title', type: 'TEXT', required: true },
        { code: 'detail', label: 'Detail', type: 'TEXT' },
        { code: 'secret', label: 'Secret', type: 'TEXT' },
      ],
    },
  ],
};

const apiErr = (status: number, extra: Partial<ApiError> = {}): ApiError => ({
  status,
  code: 'X',
  message: 'm',
  violations: [],
  ...extra,
});

function setup(api: Partial<Record<'publicApp' | 'submitForm', unknown>>, form = 'REPORT') {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: ApiService, useValue: api },
      { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ app: 'GRV', form }) } } },
    ],
  });
  const fixture = TestBed.createComponent(PublicFormPage);
  fixture.detectChanges();
  return fixture;
}

const el = (f: ComponentFixture<PublicFormPage>) => f.nativeElement as HTMLElement;
const button = (f: ComponentFixture<PublicFormPage>) => el(f).querySelector('button.sk-public-submit') as HTMLButtonElement;

function fill(f: ComponentFixture<PublicFormPage>, value: string) {
  const input = el(f).querySelector('input') as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  f.detectChanges();
}

describe('PublicFormPage', () => {
  it('shows "not available" and no controls when the form code is missing', () => {
    const f = setup({ publicApp: () => of(subset) }, 'OTHER');
    expect(el(f).textContent).toContain('not available');
    expect(el(f).querySelector('input')).toBeNull();
  });

  it.each([404, 409])('shows "not available" on status %i', (status) => {
    const f = setup({ publicApp: () => throwError(() => apiErr(status)) });
    expect(el(f).textContent).toContain('not available');
    expect(el(f).querySelector('input')).toBeNull();
  });

  it('submits only listed fields and shows the reference number on 201', () => {
    const submitForm = vi.fn(() => of<SubmitResult>({ recordId: 'r', referenceNo: 'GRV-1', instanceId: null }));
    const f = setup({ publicApp: () => of(subset), submitForm });
    expect(el(f).querySelectorAll('input').length).toBe(2);
    fill(f, 'Broken light');
    button(f).click();
    f.detectChanges();
    expect(submitForm).toHaveBeenCalledWith('GRV', 'REPORT', { title: 'Broken light' });
    expect(el(f).textContent).toContain('GRV-1');
    expect(el(f).querySelector('input')).toBeNull();
  });

  it('keeps entered values on a 422 with violations', () => {
    const err = apiErr(422, { violations: [{ field: 'title', rule: 'maxLength', message: 'Too long' }] });
    const f = setup({ publicApp: () => of(subset), submitForm: () => throwError(() => err) });
    fill(f, 'abc');
    button(f).click();
    f.detectChanges();
    expect((el(f).querySelector('input') as HTMLInputElement).value).toBe('abc');
    expect(el(f).textContent).toContain('Too long');
  });

  it('shows a generic message with requestId on 5xx and keeps values', () => {
    const f = setup({
      publicApp: () => of(subset),
      submitForm: () => throwError(() => apiErr(500, { message: 'secret detail', requestId: 'req-7' })),
    });
    fill(f, 'abc');
    button(f).click();
    f.detectChanges();
    expect(el(f).textContent).toContain('req-7');
    expect(el(f).textContent).not.toContain('secret detail');
    expect((el(f).querySelector('input') as HTMLInputElement).value).toBe('abc');
  });

  it('offers "Track this request" with only the reference when the form is trackable', () => {
    const tracked: PublicSubset = { ...subset, forms: [{ ...subset.forms[0], trackingEnabled: true }] };
    const submitForm = () => of<SubmitResult>({ recordId: 'r', referenceNo: 'GRV-2026-000001', instanceId: 'i' });
    const f = setup({ publicApp: () => of(tracked), submitForm });
    fill(f, 'person@example.org');
    button(f).click();
    f.detectChanges();
    const link = el(f).querySelector('a.sk-public-track-link') as HTMLAnchorElement;
    expect(link).not.toBeNull();
    expect(link.textContent).toContain('Track this request');
    expect(link.getAttribute('href')).toBe('/public/GRV/track?reference=GRV-2026-000001');
    expect(link.getAttribute('href')).not.toContain('example.org');
    expect(el(f).textContent).toContain('You will need the email address you gave on the form.');
  });

  it('shows no tracking link when the form is not trackable', () => {
    const submitForm = () => of<SubmitResult>({ recordId: 'r', referenceNo: 'GRV-1', instanceId: null });
    const f = setup({ publicApp: () => of(subset), submitForm });
    fill(f, 'abc');
    button(f).click();
    f.detectChanges();
    expect(el(f).querySelector('a.sk-public-track-link')).toBeNull();
    expect(el(f).textContent).not.toContain('email address you gave');
  });

  it('disables the submit button while the request is pending', () => {
    const pending = new Subject<SubmitResult>();
    const f = setup({ publicApp: () => of(subset), submitForm: () => pending });
    fill(f, 'abc');
    expect(button(f).disabled).toBe(false);
    button(f).click();
    f.detectChanges();
    expect(button(f).disabled).toBe(true);
  });
});
