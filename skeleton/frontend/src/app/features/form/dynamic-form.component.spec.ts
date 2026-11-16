import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import type { ApiError } from '../../core/api/api.types';
import { DynamicFormComponent } from './dynamic-form.component';
import type { FieldDef } from './form-model';

const f = (code: string, extra: Partial<FieldDef> = {}): FieldDef => ({
  code,
  label: code,
  type: 'TEXT',
  ...extra,
});

function setup(fields: FieldDef[], rules?: Record<string, 'hidden' | 'readonly' | 'optional' | 'required'>) {
  const fixture = TestBed.createComponent(DynamicFormComponent);
  fixture.componentRef.setInput('fields', fields);
  if (rules) {
    fixture.componentRef.setInput('rules', rules);
  }
  fixture.detectChanges();
  const emitted: Record<string, unknown>[] = [];
  fixture.componentInstance.submitted.subscribe((v) => emitted.push(v));
  return { fixture, cmp: fixture.componentInstance, emitted };
}

describe('DynamicFormComponent', () => {
  it('emits nothing and marks controls touched when invalid', () => {
    const { cmp, emitted } = setup([f('name', { required: true })]);
    cmp.submit();
    expect(emitted).toEqual([]);
    expect(cmp.valid()).toBe(false);
    expect(cmp.form().controls['name'].touched).toBe(true);
  });

  it('emits JSON-typed non-empty values when valid', () => {
    const { cmp, emitted } = setup([
      f('name', { required: true }),
      f('age', { type: 'NUMBER' }),
      f('note'),
    ]);
    cmp.form().controls['name'].setValue('Ann');
    cmp.form().controls['age'].setValue(42);
    cmp.submit();
    expect(emitted).toEqual([{ name: 'Ann', age: 42 }]);
    expect(cmp.values()).toEqual({ name: 'Ann', age: 42 });
  });

  it('creates no control for hidden fields', () => {
    const { cmp } = setup([f('a'), f('b')], { b: 'hidden' });
    expect(Object.keys(cmp.form().controls)).toEqual(['a']);
  });

  describe('server errors', () => {
    const apiError = (violations: { field: string; rule: string; message?: string }[], status = 400): ApiError => ({
      status,
      code: 'VALIDATION',
      message: 'Invalid',
      violations,
    });

    it('sets the server error on a matched control and marks it touched', () => {
      const { fixture, cmp } = setup([f('name'), f('city')]);
      fixture.componentRef.setInput('serverError', apiError([{ field: 'name', rule: 'x', message: 'Name taken' }]));
      fixture.detectChanges();
      const c = cmp.form().controls['name'];
      expect(c.errors?.['server']).toEqual(['Name taken']);
      expect(c.touched).toBe(true);
      expect(cmp.form().controls['city'].errors).toBeNull();
      expect(cmp.unmatchedErrors()).toEqual([]);
      expect(fixture.nativeElement.querySelector('sk-error-alert nz-alert')).toBeNull();
    });

    it('shows unmatched and hidden-field violations in the form alert', () => {
      const { fixture, cmp } = setup([f('a'), f('b')], { b: 'hidden' });
      fixture.componentRef.setInput(
        'serverError',
        apiError([
          { field: 'b', rule: 'x', message: 'Hidden bad' },
          { field: 'zzz', rule: 'x', message: 'Unknown bad' },
        ]),
      );
      fixture.detectChanges();
      expect(cmp.unmatchedErrors()).toEqual(['Hidden bad', 'Unknown bad']);
      const text = (fixture.nativeElement as HTMLElement).querySelector('sk-error-alert')?.textContent ?? '';
      expect(text).toContain('Hidden bad');
      expect(text).toContain('Unknown bad');
    });

    it('clears the server error when the user edits the control, keeping client errors', () => {
      const { fixture, cmp } = setup([f('name', { required: true })]);
      fixture.componentRef.setInput('serverError', apiError([{ field: 'name', rule: 'x', message: 'Bad' }]));
      fixture.detectChanges();
      const c = cmp.form().controls['name'];
      expect(c.errors?.['server']).toEqual(['Bad']);
      c.setValue('');
      expect(c.errors?.['server']).toBeUndefined();
      expect(c.errors?.['required']).toBe(true);
      c.setValue('ok');
      expect(c.errors).toBeNull();
    });

    it('clears applied server errors when serverError becomes null', () => {
      const { fixture, cmp } = setup([f('name')]);
      fixture.componentRef.setInput('serverError', apiError([{ field: 'name', rule: 'x', message: 'Bad' }]));
      fixture.detectChanges();
      fixture.componentRef.setInput('serverError', null);
      fixture.detectChanges();
      expect(cmp.form().controls['name'].errors).toBeNull();
      expect(cmp.unmatchedErrors()).toEqual([]);
    });

    it('shows an error without violations in the form alert', () => {
      const { fixture } = setup([f('name')]);
      fixture.componentRef.setInput('serverError', apiError([], 409));
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('sk-error-alert nz-alert')).not.toBeNull();
    });
  });

  it('keeps the values of unchanged codes when inputs change', () => {
    const { fixture, cmp } = setup([f('a'), f('b')]);
    cmp.form().controls['a'].setValue('kept');
    fixture.componentRef.setInput('rules', { b: 'required' });
    fixture.detectChanges();
    expect(cmp.form().controls['a'].value).toBe('kept');
    expect(cmp.valid()).toBe(false);
  });
});
