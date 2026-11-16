import { signal } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api/api.service';
import type { ApiError, RestrictedDefinition, SubmitResult } from '../../core/api/api.types';
import { SessionService } from '../../core/auth/session.service';
import { AuthedFormPage } from './authed-form.page';

const def: RestrictedDefinition = {
  app: { code: 'GRV', name: 'Grievance' },
  roles: [{ code: 'CLERK', label: 'Clerk' }],
  entities: [
    {
      code: 'GRIEVANCE',
      label: 'Grievance',
      fields: [
        { code: 'title', label: 'Title', type: 'TEXT', required: true },
        { code: 'detail', label: 'Detail', type: 'TEXT' },
      ],
    },
  ],
  forms: [
    { code: 'INTAKE', label: 'Intake', entity: 'GRIEVANCE', roles: ['CLERK'], sections: [{ fields: ['title', 'detail'] }] },
    { code: 'OTHER', label: 'Other', entity: 'GRIEVANCE', roles: ['BOSS'], sections: [{ fields: ['title'] }] },
  ],
  views: [],
  workflows: [],
};

const apiErr = (status: number, extra: Partial<ApiError> = {}): ApiError => ({
  status,
  code: 'X',
  message: 'm',
  violations: [],
  ...extra,
});

function setup(api: Record<string, unknown>, form = 'INTAKE') {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: ApiService, useValue: { definition: () => of(def), ...api } },
      { provide: SessionService, useValue: { roles: signal(['CLERK']) } },
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: { paramMap: convertToParamMap({ form }) },
          parent: { snapshot: { paramMap: convertToParamMap({ app: 'GRV' }) } },
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(AuthedFormPage);
  fixture.detectChanges();
  return fixture;
}

const el = (f: ComponentFixture<AuthedFormPage>) => f.nativeElement as HTMLElement;
const button = (f: ComponentFixture<AuthedFormPage>) => el(f).querySelector('button.sk-authed-submit') as HTMLButtonElement;

function fill(f: ComponentFixture<AuthedFormPage>, value: string) {
  const input = el(f).querySelector('input') as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  f.detectChanges();
}

describe('AuthedFormPage', () => {
  it.each(['MISSING', 'OTHER'])('shows "not available" for form %s', (form) => {
    const f = setup({}, form);
    expect(el(f).textContent).toContain('not available');
    expect(el(f).querySelector('input')).toBeNull();
  });

  it('shows the reference number and a link home on success', () => {
    const submitForm = vi.fn(() => of<SubmitResult>({ recordId: 'r', referenceNo: 'GRV-9', instanceId: null }));
    const f = setup({ submitForm });
    fill(f, 'Broken light');
    button(f).click();
    f.detectChanges();
    expect(submitForm).toHaveBeenCalledWith('GRV', 'INTAKE', { title: 'Broken light' });
    expect(el(f).textContent).toContain('GRV-9');
    expect(el(f).querySelector('a')?.getAttribute('href')).toBe('/app/GRV');
  });

  it('keeps values and shows the violation on 422', () => {
    const err = apiErr(422, { violations: [{ field: 'title', rule: 'maxLength', message: 'Too long' }] });
    const f = setup({ submitForm: () => throwError(() => err) });
    fill(f, 'abc');
    button(f).click();
    f.detectChanges();
    expect((el(f).querySelector('input') as HTMLInputElement).value).toBe('abc');
    expect(el(f).textContent).toContain('Too long');
  });

  it('disables the submit button while pending', () => {
    const f = setup({ submitForm: () => new Subject<SubmitResult>() });
    fill(f, 'abc');
    expect(button(f).disabled).toBe(false);
    button(f).click();
    f.detectChanges();
    expect(button(f).disabled).toBe(true);
  });
});
