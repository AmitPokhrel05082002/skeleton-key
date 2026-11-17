import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { NzPopconfirmDirective } from 'ng-zorro-antd/popconfirm';
import { beforeEach, describe, expect, it } from 'vitest';
import { FormBuilderComponent } from './form-builder.component';
import { formatJson } from './json-editor-helpers';

const DEF = {
  schemaVersion: 1,
  app: { code: 'DEMO', name: 'Demo' },
  roles: [{ code: 'ADMIN', label: 'Admin' }],
  entities: [
    {
      code: 'THING',
      label: 'Thing',
      fields: [
        { code: 'title', label: 'Title', type: 'TEXT' },
        { code: 'notes', label: 'Notes', type: 'TEXTAREA' },
      ],
    },
  ],
  forms: [{ code: 'THING_FORM', entity: 'THING', sections: [{ fields: ['title', 'notes'] }] }],
  workflows: [{ code: 'WF', keep: 'untouched' }],
};

describe('FormBuilderComponent', () => {
  let fixture: ComponentFixture<FormBuilderComponent>;
  let emitted: string[];

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const q = <T extends HTMLElement>(sel: string): T => el().querySelector(sel) as T;
  const all = (sel: string): HTMLElement[] => Array.from(el().querySelectorAll<HTMLElement>(sel));

  function create(text: string): void {
    fixture = TestBed.createComponent(FormBuilderComponent);
    emitted = [];
    fixture.componentInstance.applied.subscribe((t) => emitted.push(t));
    fixture.componentRef.setInput('text', text);
    fixture.detectChanges();
  }

  function typeInto(input: HTMLInputElement, value: string): void {
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  /** Drives an NG-ZORRO control the way its ControlValueAccessor does: fires ngModelChange on the host. */
  function emit(selector: string, value: unknown, index = 0): void {
    const hosts = fixture.debugElement.queryAll((d) => d.nativeElement.matches?.(selector) === true);
    hosts[index].triggerEventHandler('ngModelChange', value);
    fixture.detectChanges();
  }

  function labels(): string[] {
    return all('.sk-item-label').map((e) => e.textContent?.trim() ?? '');
  }

  beforeEach(() => TestBed.configureTestingModule({}));

  it('adds a field and writes it into the text through applyModel, keeping other content', () => {
    create(formatJson(DEF));
    q('.sk-add-field').click();
    fixture.detectChanges();
    expect(emitted).toEqual([]);
    q('.sk-apply').click();
    const out = JSON.parse(emitted[0]) as typeof DEF;
    expect(out.entities[0].fields.map((f) => f.code)).toEqual(['title', 'notes', 'field1']);
    expect(out.forms[0].sections[0].fields).toEqual(['title', 'notes', 'field1']);
    expect(out.workflows).toEqual(DEF.workflows);
    expect(out.roles).toEqual(DEF.roles);
  });

  it('refuses an invalid or duplicate code and shows the error on the input', () => {
    create(formatJson(DEF));
    const code = all('input.sk-code')[1] as HTMLInputElement;
    typeInto(code, 'title');
    expect(q('.sk-code-error').textContent).toContain('already used');
    typeInto(code, 'Bad code');
    expect(q('.sk-code-error')).not.toBeNull();
    expect((q('.sk-apply') as HTMLButtonElement).disabled).toBe(true);
    expect(labels()).toEqual(['Title', 'Notes']);
    typeInto(code, 'remarks');
    expect(el().querySelector('.sk-code-error')).toBeNull();
    q('.sk-apply').click();
    const out = JSON.parse(emitted[0]) as typeof DEF;
    expect(out.entities[0].fields.map((f) => f.code)).toEqual(['title', 'remarks']);
    expect(out.forms[0].sections[0].fields).toEqual(['title', 'remarks']);
  });

  it('shows validateField errors for a SELECT without options and blocks Apply', () => {
    create(formatJson(DEF));
    emit('.sk-add-type', 'SELECT');
    q('.sk-add-field').click();
    fixture.detectChanges();
    expect(q('.sk-field-error').textContent).toContain('option');
    expect((q('.sk-apply') as HTMLButtonElement).disabled).toBe(true);
    q('.sk-add-option').click();
    fixture.detectChanges();
    const [value, label] = all('.sk-option input') as HTMLInputElement[];
    typeInto(value, 'a');
    typeInto(label, 'A');
    expect((q('.sk-apply') as HTMLButtonElement).disabled).toBe(false);
  });

  it('uses NG-ZORRO controls and no native select, checkbox or number input', () => {
    create(formatJson(DEF));
    emit('.sk-add-type', 'DECIMAL');
    q('.sk-add-field').click();
    fixture.detectChanges();
    expect(el().querySelectorAll('nz-select').length).toBeGreaterThanOrEqual(3);
    expect(el().querySelectorAll('label[nz-checkbox]').length).toBeGreaterThan(0);
    expect(el().querySelectorAll('section.sk-field > nz-input-number').length).toBe(4);
    expect(el().querySelectorAll('select')).toHaveLength(0);
    expect(el().querySelectorAll('input[type=checkbox]:not(.ant-checkbox-input)')).toHaveLength(0);
    expect(el().querySelectorAll('input[type=number]:not(.ant-input-number-input)')).toHaveLength(0);
  });

  it('applies required, min, max and the reference target chosen in the NG-ZORRO controls', () => {
    create(formatJson(DEF));
    emit('.sk-add-type', 'DECIMAL');
    q('.sk-add-field').click();
    fixture.detectChanges();
    emit('.sk-required', true, 2);
    emit('.sk-min', 1);
    emit('.sk-max', 9);
    emit('.sk-add-type', 'REFERENCE');
    q('.sk-add-field').click();
    fixture.detectChanges();
    expect((q('.sk-apply') as HTMLButtonElement).disabled).toBe(true);
    emit('.sk-ref', 'THING');
    q('.sk-apply').click();
    const fields = (JSON.parse(emitted[0]) as typeof DEF).entities[0].fields as Record<string, unknown>[];
    expect(fields[2]).toMatchObject({ type: 'DECIMAL', required: true, min: 1, max: 9 });
    expect(fields[3]).toMatchObject({ type: 'REFERENCE', ref: 'THING' });
  });

  it('moves a field up and down with the buttons', () => {
    create(formatJson(DEF));
    expect(labels()).toEqual(['Title', 'Notes']);
    expect(q<HTMLButtonElement>('button.sk-up').getAttribute('aria-label')).toBe('Move Title up');
    all('button.sk-down')[0].click();
    fixture.detectChanges();
    expect(labels()).toEqual(['Notes', 'Title']);
    all('button.sk-up')[1].click();
    fixture.detectChanges();
    expect(labels()).toEqual(['Title', 'Notes']);
    all('button.sk-down')[0].click();
    fixture.detectChanges();
    q('.sk-apply').click();
    expect((JSON.parse(emitted[0]) as typeof DEF).forms[0].sections[0].fields).toEqual(['notes', 'title']);
  });

  it('deletes a field from the entity only after the popconfirm is confirmed', () => {
    create(formatJson(DEF));
    const del = fixture.debugElement.queryAll((d) => d.nativeElement.classList?.contains('sk-delete'))[0];
    expect(all('section.sk-field').length).toBe(2);
    del.injector.get(NzPopconfirmDirective).nzOnConfirm.emit();
    fixture.detectChanges();
    expect(all('section.sk-field').length).toBe(1);
    q('.sk-apply').click();
    const out = JSON.parse(emitted[0]) as typeof DEF;
    expect(out.entities[0].fields.map((f) => f.code)).toEqual(['notes']);
    expect(out.forms[0].sections[0].fields).toEqual(['notes']);
  });

  it('removes a field from the form but keeps it in the entity', () => {
    create(formatJson(DEF));
    q('button.sk-remove').click();
    fixture.detectChanges();
    q('.sk-apply').click();
    const out = JSON.parse(emitted[0]) as typeof DEF;
    expect(out.entities[0].fields.length).toBe(2);
    expect(out.forms[0].sections[0].fields).toEqual(['notes']);
  });

  it('renders the fields in the live preview', () => {
    create(formatJson(DEF));
    const preview = q('.sk-preview');
    expect(preview.querySelectorAll('sk-field-control').length).toBe(2);
    q('.sk-add-field').click();
    fixture.detectChanges();
    expect(preview.querySelectorAll('sk-field-control').length).toBe(3);
  });

  it('is disabled with a message for invalid JSON or no entities', () => {
    create('{ "a": ');
    expect(q('.sk-builder-disabled')).not.toBeNull();
    expect(el().querySelector('.sk-apply')).toBeNull();
    fixture.componentRef.setInput('text', JSON.stringify({ entities: [] }));
    fixture.detectChanges();
    expect(q('.sk-builder-disabled')).not.toBeNull();
    fixture.componentRef.setInput('text', formatJson(DEF));
    fixture.detectChanges();
    expect(el().querySelector('.sk-builder-disabled')).toBeNull();
    expect(q('.sk-apply')).not.toBeNull();
  });
});
