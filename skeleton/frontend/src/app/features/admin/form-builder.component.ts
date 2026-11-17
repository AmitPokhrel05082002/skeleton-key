import { CdkDrag, CdkDragDrop, CdkDropList } from '@angular/cdk/drag-drop';
import { ChangeDetectionStrategy, Component, computed, input, linkedSignal, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzPopconfirmModule } from 'ng-zorro-antd/popconfirm';
import { NzSelectModule } from 'ng-zorro-antd/select';
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
  imports: [
    CdkDropList,
    CdkDrag,
    DynamicFormComponent,
    FormsModule,
    NzAlertModule,
    NzButtonModule,
    NzCheckboxModule,
    NzInputModule,
    NzInputNumberModule,
    NzPopconfirmModule,
    NzSelectModule,
  ],
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
        <label for="sk-entity-select">Entity</label>
        <nz-select
          class="sk-entity sk-select"
          nzId="sk-entity-select"
          aria-label="Entity"
          [ngModel]="entityCode()"
          (ngModelChange)="pickEntity($event)"
        >
          @for (e of entities(); track e.code) {
            <nz-option [nzValue]="e.code" [nzLabel]="e.label" />
          }
        </nz-select>
        <label for="sk-form-select">Form</label>
        <nz-select
          class="sk-form sk-select"
          nzId="sk-form-select"
          aria-label="Form"
          [ngModel]="formCode() ?? newForm"
          (ngModelChange)="pickForm($event)"
        >
          <nz-option [nzValue]="newForm" nzLabel="New form" />
          @for (f of forms(); track f.code) {
            <nz-option [nzValue]="f.code" [nzLabel]="f.label" />
          }
        </nz-select>
      </div>

      <div class="sk-add">
        <label for="sk-add-type-select" class="sk-sr">Field type</label>
        <nz-select
          class="sk-add-type sk-select"
          nzId="sk-add-type-select"
          aria-label="Field type"
          [ngModel]="addType()"
          (ngModelChange)="addType.set($event)"
        >
          @for (t of types; track t) {
            <nz-option [nzValue]="t" [nzLabel]="t" />
          }
        </nz-select>
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
          <!-- nz-checkbox renders its own <input type="checkbox"> inside this label. -->
          <!-- eslint-disable-next-line @angular-eslint/template/label-has-associated-control -->
          <label
            nz-checkbox
            class="sk-required"
            [ngModel]="f.required === true"
            (ngModelChange)="patch(i, { required: $event === true })"
          >
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
            <nz-input-number class="sk-min" [nzId]="'sk-min-' + i" aria-label="Min" nzPlaceHolder="Min" [ngModel]="f.min ?? null" (ngModelChange)="setNum(i, 'min', $event)" />
            <nz-input-number class="sk-max" [nzId]="'sk-max-' + i" aria-label="Max" nzPlaceHolder="Max" [ngModel]="f.max ?? null" (ngModelChange)="setNum(i, 'max', $event)" />
          }
          @if (textual(f)) {
            <nz-input-number class="sk-maxlength" [nzId]="'sk-maxlength-' + i" aria-label="Max length" nzPlaceHolder="Max length" [nzMin]="1" [nzPrecision]="0" [ngModel]="f.maxLength ?? null" (ngModelChange)="setNum(i, 'maxLength', $event)" />
          }
          @if (f.type === 'TEXT') {
            <input nz-input class="sk-pattern" aria-label="Pattern" placeholder="Pattern" [value]="f.pattern ?? ''" (input)="patch(i, { pattern: val($event) || undefined })" />
          }
          @if (f.type === 'REFERENCE') {
            <nz-select
              class="sk-ref sk-select"
              [nzId]="'sk-ref-' + i"
              aria-label="Target entity"
              nzPlaceHolder="Choose entity"
              nzAllowClear
              [ngModel]="f.ref ?? null"
              (ngModelChange)="patch(i, { ref: $event || undefined })"
            >
              @for (e of entities(); track e.code) {
                <nz-option [nzValue]="e.code" [nzLabel]="e.label" />
              }
            </nz-select>
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
      border: 1px solid var(--sk-divider);
      padding: 8px;
      margin-bottom: 8px;
    }
    .sk-field {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 8px;
    }
    .sk-field > .sk-row,
    .sk-field > input {
      align-self: stretch;
    }
    .sk-row > input {
      flex: 1;
    }
    .sk-select {
      min-width: 12rem;
    }
    .sk-sr {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip-path: inset(50%);
      white-space: nowrap;
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
      color: var(--sk-error-fg);
    }
  `,
})
export class FormBuilderComponent {
  /** The editor text. */
  readonly text = input.required<string>();
  /** Emits the whole new editor text when the user applies the builder result. */
  readonly applied = output<string>();

  protected readonly types = FIELD_TYPES;
  /** Option value of "New form" in the form select (a real form code never starts with underscores). */
  protected readonly newForm = '__NEW_FORM__';
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
    return (e.target as HTMLInputElement).value;
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
  protected pickForm(code: string | null): void {
    this.pickedForm.set(code === null || code === this.newForm ? null : code);
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

  protected setNum(i: number, key: NumKey, n: number | null): void {
    this.patch(i, { [key]: n === null || Number.isNaN(n) ? undefined : n });
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
