import { TestBed } from '@angular/core/testing';
import { FormControl, Validators } from '@angular/forms';
import { describe, expect, it } from 'vitest';
import { errorMessages, FieldControlComponent } from './field-control.component';
import type { FieldDef, FieldRule } from './form-model';

function setup(field: FieldDef, control: FormControl = new FormControl(null), rule: FieldRule = 'optional') {
  const fixture = TestBed.createComponent(FieldControlComponent);
  fixture.componentRef.setInput('field', field);
  fixture.componentRef.setInput('control', control);
  fixture.componentRef.setInput('rule', rule);
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement, control };
}

describe('FieldControlComponent', () => {
  it('renders a text input with label and help', () => {
    const { el } = setup({ code: 'name', label: 'Name', type: 'TEXT', help: 'Your name' });
    expect(el.querySelector('input#sk-f-name')).not.toBeNull();
    expect(el.querySelector('label')?.textContent).toContain('Name');
    expect(el.textContent).toContain('Your name');
  });

  it('renders FILE as a disabled input with the hint', () => {
    const { el } = setup({ code: 'doc', label: 'Doc', type: 'FILE' });
    expect((el.querySelector('input') as HTMLInputElement).disabled).toBe(true);
    expect(el.textContent).toContain('File upload is not yet available');
  });

  it('renders two inputs for LOCATION', () => {
    const { el } = setup({ code: 'loc', label: 'Loc', type: 'LOCATION' });
    expect(el.querySelectorAll('nz-input-number').length).toBe(2);
  });

  it('shows client errors when touched, wired with aria attributes', () => {
    const control = new FormControl('', Validators.required);
    const { fixture, el } = setup({ code: 'name', label: 'Name', type: 'TEXT' }, control, 'required');
    expect(el.querySelector('#name-error')).toBeNull();
    control.markAsTouched();
    fixture.detectChanges();
    expect(el.querySelector('#name-error')?.textContent).toContain('required');
    const input = el.querySelector('input') as HTMLInputElement;
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(input.getAttribute('aria-describedby')).toBe('name-error');
  });

  it('shows server errors', () => {
    const control = new FormControl('x');
    const { fixture, el } = setup({ code: 'name', label: 'Name', type: 'TEXT' }, control);
    control.setErrors({ server: ['Already taken'] });
    fixture.detectChanges();
    expect(el.querySelector('#name-error')?.textContent).toContain('Already taken');
  });

  it('maps validator errors to messages', () => {
    expect(errorMessages({ maxlength: { requiredLength: 5, actualLength: 6 } })).toEqual([
      'Must be at most 5 characters',
    ]);
    expect(errorMessages({ min: { min: 1, actual: 0 } })).toEqual(['Must be at least 1']);
  });
});
