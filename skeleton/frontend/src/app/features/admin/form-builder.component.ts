import { CdkDrag, CdkDragDrop, CdkDropList } from '@angular/cdk/drag-drop';
import { ChangeDetectionStrategy, Component, computed, input, linkedSignal, output, signal } from '@angular/core';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzPopconfirmModule } from 'ng-zorro-antd/popconfirm';
import type { FieldDef, FieldType, SectionDef } from '../../core/api/api.types';
import { DynamicFormComponent } from '../form/dynamic-form.component';
import {
  AppDefinitionJson,
  BuilderModel,
  FIELD_TYPES,
  applyModel,
  extractModel,
  generateFieldCode,
  moveField,
  validateField,
  validateFieldCode,
} from './builder-model';
import { formatJson, jsonState } from './json-editor-helpers';

type NumKey = 'min' | 'max' | 'maxLength';

const NUMERIC: readonly FieldType[] = ['NUMBER', 'DECIMAL'];
const TEXTUAL: readonly FieldType[] = ['TEXT', 'TEXTAREA', 'EMAIL', 'PHONE'];

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function list(v: unknown): Record<string, unknown>[] {
  return Array.isArray(v) ? v.filter(isRecord) : [];
}

/**
 * Minimal form builder (Requirement 15). It reads the editor text through the `text` input and hands the new
 * text back through `applied`; the host passes that to `JsonEditorComponent.setText`. Edits go to a local
 * working model, and only the Apply button writes them, through `applyModel`, into the document.
 */
@Component({
  selector: 'sk-form-builder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CdkDropList, CdkDrag, DynamicFormComponent, NzAlertModule, NzButtonModule, NzInputModule, NzPopconfirmModule],
  template: `
    <h3>Form builder</h3>
    @if (!enabled()) {
      <nz-alert
        class="sk-builder-disabled"
        nzType="info"
        nzMessage="The form builder needs valid JSON with at least one entity."
        role="status"
      />
    } @else if (working(); as m) {
      <div class="sk-pick">
        <label>
          Entity
          <select class="sk-entity" aria-label="Entity" (change)="pickEntity(val($event))">
            @for (e of entities(); track e.code) {
              <option [value]="e.code" [selected]="e.code === entityCode()">{{ e.label }}</option>
            }
          </select>
        </label>
        <label>
          Form
          <select class="sk-form" aria-label="Form" (change)="pickForm(val($event))">
            <option value="" [selected]="formCode() === null">New form</option>
            @for (f of forms(); track f.code) {
              <option [value]="f.code" [selected]="f.code === formCode()">{{ f.label }}</option>
            }
          </select>
        </label>
      </div>

      <div class="sk-add">
        <select class="sk-add-type" aria-label="Field type" (change)="addType.set(val($event))">
          @for (t of types; track t) {
            <option [value]="t" [selected]="t === addType()">{{ t }}</option>
          }
        </select>
        <button nz-button type="button" class="sk-add-field" (click)="addField()">Add field</button>
      </div>

      @for (f of m.fields; track $index; let i = $index) {
        <section class="sk-field" [attr.aria-label]="'Field ' + f.code">
          <div class="sk-row">
            <input
              nz-input
              class="sk-code"
              aria-label="Field code"
              [value]="f.code"
              (input)="setCode(i, val($event))"
            />
            <span class="sk-type">{{ f.type }}</span>
            <button
              nz-button
              nzDanger
              type="button"
              class="sk-delete"
              [attr.aria-label]="'Delete ' + f.code + ' from entity'"
              nz-popconfirm
              nzPopconfirmTitle="Delete this field from the entity?"
              (nzOnConfirm)="deleteField(i)"
            >
              Delete
            </button>
          </div>
          @if (codeErrors()[i]; as ce) {
            <div class="sk-error sk-code-error" role="alert">{{ ce }}</div>
          }
          <input
            nz-input
            class="sk-label"
            aria-label="Label"
            [value]="f.label"
            (input)="patch(i, { label: val($event) })"
          />
          <label>
            <input
              type="checkbox"
              class="sk-required"
              [checked]="f.required === true"
              (change)="patch(i, { required: checked($event) })"
            />
            Required
          </label>
          <input
            nz-input
            class="sk-help"
            aria-label="Help"
            placeholder="Help"
            [value]="f.help ?? ''"
            (input)="patch(i, { help: val($event) || undefined })"
          />
          @if (numeric(f)) {
            <input nz-input class="sk-min" type="number" aria-label="Min" placeholder="Min" [value]="f.min ?? ''" (input)="setNum(i, 'min', val($event))" />
            <input nz-input class="sk-max" type="number" aria-label="Max" placeholder="Max" [value]="f.max ?? ''" (input)="setNum(i, 'max', val($event))" />
          }
          @if (textual(f)) {
            <input nz-input class="sk-maxlength" type="number" aria-label="Max length" placeholder="Max length" [value]="f.maxLength ?? ''" (input)="setNum(i, 'maxLength', val($event))" />
          }
          @if (f.type === 'TEXT') {
            <input nz-input class="sk-pattern" aria-label="Pattern" placeholder="Pattern" [value]="f.pattern ?? ''" (input)="patch(i, { pattern: val($event) || undefined })" />
          }
          @if (f.type === 'REFERENCE') {
            <select class="sk-ref" aria-label="Target entity" (change)="patch(i, { ref: val($event) || undefined })">
              <option value="" [selected]="!f.ref">Choose entity</option>
              @for (e of entities(); track e.code) {
                <option [value]="e.code" [selected]="e.code === f.ref">{{ e.label }}</option>
              }
            </select>
          }
          @if (f.type === 'SELECT' || f.type === 'MULTISELECT') {
            @for (o of f.options ?? []; track $index; let oi = $index) {
              <div class="sk-row sk-option">
                <input nz-input aria-label="Option value" placeholder="Value" [value]="o.value" (input)="setOption(i, oi, 'value', val($event))" />
                <input nz-input aria-label="Option label" placeholder="Label" [value]="o.label" (input)="setOption(i, oi, 'label', val($event))" />
                <button nz-button type="button" aria-label="Remove option" (click)="removeOption(i, oi)">Remove</button>
              </div>
            }
            <button nz-button type="button" class="sk-add-option" (click)="addOption(i)">Add option</button>
          }
          @for (err of fieldErrors()[i]; track err) {
            <div class="sk-error sk-field-error" role="alert">{{ err }}</div>
          }
          @if (formCode() !== null && !inForm().has(f.code)) {
            <button nz-button type="button" class="sk-add-to-form" (click)="addToForm(f.code)">Add to form</button>
          }
        </section>
      }

      @if (formCode() !== null) {
        <h4>Form layout</h4>
        @for (s of m.sections; track $index; let si = $index) {
          <div class="sk-section" cdkDropList [cdkDropListData]="s.fields" (cdkDropListDropped)="drop(si, $event)">
            @for (code of s.fields; track code; let fi = $index; let first = $first; let last = $last) {
              <div class="sk-item" cdkDrag>
                <span class="sk-item-label">{{ labelOf(code) }}</span>
                <button nz-button type="button" class="sk-up" [disabled]="first" [attr.aria-label]="'Move ' + labelOf(code) + ' up'" (click)="move(si, fi, fi - 1)">Up</button>
                <button nz-button type="button" class="sk-down" [disabled]="last" [attr.aria-label]="'Move ' + labelOf(code) + ' down'" (click)="move(si, fi, fi + 1)">Down</button>
                <button nz-button type="button" class="sk-remove" [attr.aria-label]="'Remove ' + labelOf(code) + ' from form'" (click)="removeFromForm(si, fi)">Remove</button>
              </div>
            }
          </div>
        }
      }

      <button nz-button nzType="primary" type="button" class="sk-apply" [disabled]="!canApply()" (click)="apply()">Apply</button>

      <h4>Preview</h4>
      <div class="sk-preview">
        <sk-dynamic-form [fields]="previewFields()" [sections]="previewSections()" />
      </div>
    }
  `,
  styles: `
    .sk-field,
    .sk-item {
      border: 1px solid #eee;
      padding: 8px;
      margin-bottom: 8px;
    }
    .sk-row,
    .sk-pick,
    .sk-add,
    .sk-item {
      display: flex;
      gap: 8px;
      align-items: center;
    }
    .sk-item-label {
      flex: 1;
    }
    .sk-error {
      color: #cf1322;
    }
  `,
})
export class FormBuilderComponent {
  /** The editor text. */
  readonly text = input.required<string>();
  /** Emits the whole new editor text when the user applies the builder result. */
  readonly applied = output<string>();

  protected readonly types = FIELD_TYPES;
  protected readonly addType = signal<string>('TEXT');

  private readonly def = computed<AppDefinitionJson | null>(() => {
    const p = jsonState(this.text());
    return p.valid && isRecord(p.value) ? p.value : null;
  });

  protected readonly entities = computed(() =>
    list(this.def()?.['entities']).map((e) => ({ code: String(e['code']), label: String(e['label'] ?? e['code']) })),
  );
  protected readonly enabled = computed(() => this.entities().length > 0);

  private readonly pickedEntity = signal<string | null>(null);
  private readonly pickedForm = signal<string | null | undefined>(undefined);

  protected readonly entityCode = computed(() => {
    const all = this.entities();
    const p = this.pickedEntity();
    return all.find((e) => e.code === p)?.code ?? all[0]?.code ?? null;
  });
  protected readonly forms = computed(() =>
    list(this.def()?.['forms'])
      .filter((f) => f['entity'] === this.entityCode())
      .map((f) => ({ code: String(f['code']), label: String(f['label'] ?? f['code']) })),
  );
  /** Selected form code. `null` is "New form"; before any choice the first form of the entity is used. */
  protected readonly formCode = computed<string | null>(() => {
    const all = this.forms();
    const p = this.pickedForm();
    if (p === undefined) return all[0]?.code ?? null;
    return all.find((f) => f.code === p)?.code ?? null;
  });

  private readonly source = computed<BuilderModel | null>(() => {
    const d = this.def();
    const e = this.entityCode();
    return d && e ? extractModel(d, e, this.formCode()) : null;
  });

  /** Local working model. Reset from the document whenever the text or the selection changes. */
  protected readonly working = linkedSignal<BuilderModel | null>(() => this.source());
  protected readonly codeErrors = linkedSignal<Record<number, string>>(() => {
    this.source();
    return {};
  });

  protected readonly fieldErrors = computed(() => {
    const codes = this.entities().map((e) => e.code);
    return (this.working()?.fields ?? []).map((f) => validateField(f, codes));
  });
  protected readonly canApply = computed(
    () =>
      JSON.stringify(this.working()) !== JSON.stringify(this.source()) &&
      this.fieldErrors().every((e) => e.length === 0) &&
      Object.keys(this.codeErrors()).length === 0,
  );
  protected readonly inForm = computed(() => new Set((this.working()?.sections ?? []).flatMap((s) => s.fields)));

  protected readonly previewFields = computed<FieldDef[]>(() => {
    const codes = this.entities().map((e) => e.code);
    return (this.working()?.fields ?? []).filter((f) => validateField(f, codes).length === 0);
  });
  protected readonly previewSections = computed<SectionDef[] | undefined>(() => {
    const m = this.working();
    if (!m || m.formCode === null) return undefined;
    const known = new Set(this.previewFields().map((f) => f.code));
    const sections = m.sections
      .map((s) => ({ ...s, fields: s.fields.filter((c) => known.has(c)) }))
      .filter((s) => s.fields.length > 0);
    return sections.length > 0 ? sections : undefined;
  });

  protected val(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }
  protected checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }
  protected numeric(f: FieldDef): boolean {
    return NUMERIC.includes(f.type);
  }
  protected textual(f: FieldDef): boolean {
    return TEXTUAL.includes(f.type);
  }
  protected labelOf(code: string): string {
    return this.working()?.fields.find((f) => f.code === code)?.label ?? code;
  }

  protected pickEntity(code: string): void {
    this.pickedEntity.set(code);
    this.pickedForm.set(undefined);
  }
  protected pickForm(code: string): void {
    this.pickedForm.set(code === '' ? null : code);
  }

  private edit(fn: (m: BuilderModel) => BuilderModel): void {
    this.working.update((m) => (m ? fn(m) : m));
  }
  private withFields(fn: (fields: FieldDef[]) => FieldDef[]): void {
    this.edit((m) => ({ ...m, fields: fn(m.fields) }));
  }
  private withSections(fn: (s: SectionDef[]) => SectionDef[]): void {
    this.edit((m) => ({ ...m, sections: fn(m.sections) }));
  }

  protected addField(): void {
    const m = this.working();
    if (!m) return;
    const code = generateFieldCode(m.fields.map((f) => f.code));
    const type = (FIELD_TYPES.includes(this.addType() as FieldType) ? this.addType() : 'TEXT') as FieldType;
    this.edit((x) => ({ ...x, fields: [...x.fields, { code, label: 'New field', type }] }));
    if (m.formCode !== null) this.addToForm(code);
  }

  protected addToForm(code: string): void {
    this.withSections((sections) =>
      sections.length === 0
        ? [{ fields: [code] }]
        : sections.map((s, i) => (i === sections.length - 1 ? { ...s, fields: [...s.fields, code] } : s)),
    );
  }

  protected patch(i: number, change: Partial<FieldDef>): void {
    this.withFields((fields) => fields.map((f, k) => (k === i ? { ...f, ...change } : f)));
  }

  protected setNum(i: number, key: NumKey, raw: string): void {
    const n = raw.trim() === '' ? undefined : Number(raw);
    this.patch(i, { [key]: n === undefined || Number.isNaN(n) ? undefined : n });
  }

  /** Renames a field when the new code is valid and unique; otherwise shows the error and changes nothing. */
  protected setCode(i: number, value: string): void {
    const m = this.working();
    const self = m?.fields[i];
    if (!m || !self) return;
    const error = validateFieldCode(value, m.fields.map((f) => f.code), self.code);
    if (error !== null) {
      this.codeErrors.update((e) => ({ ...e, [i]: error }));
      return;
    }
    this.codeErrors.update((errors) => {
      const rest = { ...errors };
      delete rest[i];
      return rest;
    });
    if (value === self.code) return;
    this.edit((x) => ({
      ...x,
      fields: x.fields.map((f, k) => (k === i ? { ...f, code: value } : f)),
      sections: x.sections.map((s) => ({ ...s, fields: s.fields.map((c) => (c === self.code ? value : c)) })),
    }));
  }

  /** Deletes the field from the entity and from every section of the form. */
  protected deleteField(i: number): void {
    const code = this.working()?.fields[i]?.code;
    if (code === undefined) return;
    this.codeErrors.set({});
    this.edit((x) => ({
      ...x,
      fields: x.fields.filter((_, k) => k !== i),
      sections: x.sections.map((s) => ({ ...s, fields: s.fields.filter((c) => c !== code) })),
    }));
  }

  protected removeFromForm(si: number, fi: number): void {
    this.withSections((sections) =>
      sections.map((s, k) => (k === si ? { ...s, fields: s.fields.filter((_, j) => j !== fi) } : s)),
    );
  }

  protected move(si: number, from: number, to: number): void {
    this.withSections((sections) => sections.map((s, k) => (k === si ? moveField(s, from, to) : s)));
  }

  protected drop(si: number, event: CdkDragDrop<string[]>): void {
    this.move(si, event.previousIndex, event.currentIndex);
  }

  protected addOption(i: number): void {
    const f = this.working()?.fields[i];
    this.patch(i, { options: [...(f?.options ?? []), { value: '', label: '' }] });
  }
  protected removeOption(i: number, oi: number): void {
    const f = this.working()?.fields[i];
    this.patch(i, { options: (f?.options ?? []).filter((_, k) => k !== oi) });
  }
  protected setOption(i: number, oi: number, key: 'value' | 'label', value: string): void {
    const f = this.working()?.fields[i];
    this.patch(i, { options: (f?.options ?? []).map((o, k) => (k === oi ? { ...o, [key]: value } : o)) });
  }

  protected apply(): void {
    const d = this.def();
    const m = this.working();
    if (!d || !m || !this.canApply()) return;
    this.applied.emit(formatJson(applyModel(d, m)));
  }
}
