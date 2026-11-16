import { TestBed } from '@angular/core/testing';
import { provideNzNativeDateAdapter } from 'ng-zorro-antd/core/time';
import { en_US, provideNzI18n } from 'ng-zorro-antd/i18n';
import { beforeEach, describe, expect, it } from 'vitest';
import { DynamicFormComponent } from './dynamic-form.component';
import type { FieldDef } from './form-model';

const OPTIONS = [
  { value: 'a', label: 'A' },
  { value: 'b', label: 'B' },
];

const field = (type: string, extra: Record<string, unknown> = {}): FieldDef =>
  ({ code: `f_${type.toLowerCase()}`, label: `Label ${type}`, type, ...extra }) as unknown as FieldDef;

function render(f: FieldDef): HTMLElement {
  const fixture = TestBed.createComponent(DynamicFormComponent);
  fixture.componentRef.setInput('fields', [f]);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('DynamicFormComponent field types', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideNzI18n(en_US), provideNzNativeDateAdapter()] });
  });

  const inputCases: [string, string][] = [
    ['TEXT', 'text'],
    ['EMAIL', 'email'],
    ['PHONE', 'tel'],
    ['USER', 'text'],
    ['REFERENCE', 'text'],
  ];
  for (const [type, htmlType] of inputCases) {
    it(`renders ${type} as a single text-like input`, () => {
      const el = render(field(type, type === 'REFERENCE' ? { ref: 'OTHER' } : {}));
      const inputs = el.querySelectorAll('input[nz-input]');
      expect(inputs.length).toBe(1);
      expect(inputs[0].getAttribute('type')).toBe(htmlType);
      expect(el.textContent).toContain(`Label ${type}`);
    });
  }

  it('renders TEXTAREA as a textarea', () => {
    const el = render(field('TEXTAREA'));
    expect(el.querySelectorAll('textarea[nz-input]').length).toBe(1);
  });

  it('renders NUMBER and DECIMAL as nz-input-number', () => {
    expect(render(field('NUMBER')).querySelectorAll('nz-input-number').length).toBe(1);
    expect(render(field('DECIMAL')).querySelectorAll('nz-input-number').length).toBe(1);
  });

  it('renders DATE and DATETIME as nz-date-picker', () => {
    expect(render(field('DATE')).querySelectorAll('nz-date-picker').length).toBe(1);
    expect(render(field('DATETIME')).querySelectorAll('nz-date-picker').length).toBe(1);
  });

  it('renders BOOLEAN as nz-switch', () => {
    expect(render(field('BOOLEAN')).querySelectorAll('nz-switch').length).toBe(1);
  });

  it('renders SELECT and MULTISELECT as nz-select', () => {
    const single = render(field('SELECT', { options: OPTIONS }));
    expect(single.querySelectorAll('nz-select').length).toBe(1);
    expect(single.querySelector('nz-select.ant-select-multiple')).toBeNull();
    const multi = render(field('MULTISELECT', { options: OPTIONS }));
    expect(multi.querySelectorAll('nz-select').length).toBe(1);
    expect(multi.querySelector('nz-select.ant-select-multiple')).not.toBeNull();
  });

  it('renders LOCATION as two number inputs', () => {
    const el = render(field('LOCATION'));
    expect(el.querySelectorAll('nz-input-number').length).toBe(2);
  });

  it('renders FILE as a disabled input with the explanatory text', () => {
    const el = render(field('FILE'));
    const input = el.querySelector('input[nz-input]') as HTMLInputElement;
    expect(input).not.toBeNull();
    expect(input.disabled).toBe(true);
    expect(el.textContent).toContain('File upload is not yet available');
  });

  it('renders a control for every one of the 15 types', () => {
    const types = [
      'TEXT', 'TEXTAREA', 'NUMBER', 'DECIMAL', 'DATE', 'DATETIME', 'BOOLEAN', 'SELECT',
      'MULTISELECT', 'FILE', 'USER', 'REFERENCE', 'EMAIL', 'PHONE', 'LOCATION',
    ];
    const fixture = TestBed.createComponent(DynamicFormComponent);
    fixture.componentRef.setInput(
      'fields',
      types.map((t) => field(t, { options: OPTIONS, ref: 'OTHER' })),
    );
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('sk-field-control').length).toBe(15);
    expect(el.querySelectorAll('nz-form-item').length).toBe(15);
  });
});
