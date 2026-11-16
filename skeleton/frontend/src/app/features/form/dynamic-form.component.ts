import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal, untracked } from '@angular/core';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
  type AbstractControl,
  type ValidationErrors,
  type ValidatorFn,
} from '@angular/forms';
import { NzFormModule } from 'ng-zorro-antd/form';
import type { ApiError } from '../../core/api/api.types';
import { mapErrors, type MappedErrors } from '../../core/pure/error-mapper';
import { toJsonValue } from '../../core/pure/value-codec';
import { ErrorAlertComponent } from '../../shared/error-alert/error-alert.component';
import { FieldControlComponent } from './field-control.component';
import {
  collectValues,
  initialValues,
  isEmptyValue,
  layoutSections,
  planControls,
  type ControlPlan,
  type FieldDef,
  type FieldRule,
  type SectionDef,
  type ValidatorKind,
} from './form-model';

function validatorFor(kind: ValidatorKind, field: FieldDef): ValidatorFn {
  switch (kind) {
    case 'required':
      return (c: AbstractControl): ValidationErrors | null => (isEmptyValue(c.value) ? { required: true } : null);
    case 'maxLength':
      return Validators.maxLength(field.maxLength as number);
    case 'min':
      return Validators.min(field.min as number);
    case 'max':
      return Validators.max(field.max as number);
    case 'pattern':
      return Validators.pattern(field.pattern as string);
    case 'email':
      return Validators.email;
  }
}

interface SectionView {
  title?: string;
  columns: 1 | 2 | 3;
  items: { plan: ControlPlan; control: FormControl }[];
}

@Component({
  selector: 'sk-dynamic-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, NzFormModule, FieldControlComponent, ErrorAlertComponent],
  template: `
    <sk-error-alert [error]="alertError()" [unmatched]="unmatchedErrors()" />
    <form nz-form nzLayout="vertical" [formGroup]="form()" (ngSubmit)="submit()" novalidate>
      @for (section of sectionViews(); track $index) {
        <fieldset class="sk-section">
          @if (section.title) {
            <legend class="sk-section-title">{{ section.title }}</legend>
          }
          <div class="sk-grid" [class]="'sk-grid sk-cols-' + section.columns">
            @for (item of section.items; track item.plan.code) {
              <sk-field-control
                [field]="item.plan.field"
                [control]="item.control"
                [rule]="item.plan.rule"
                [disabled]="item.plan.disabled || disabled()"
              />
            }
          </div>
        </fieldset>
      }
    </form>
  `,
  styles: `
    .sk-section {
      border: 0;
      margin: 0 0 16px;
      padding: 0;
    }
    .sk-section-title {
      font-weight: 600;
      margin-bottom: 8px;
      padding: 0;
    }
    .sk-grid {
      display: grid;
      gap: 0 16px;
      grid-template-columns: 1fr;
    }
    @media (min-width: 576px) {
      .sk-cols-2 {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
      .sk-cols-3 {
        grid-template-columns: repeat(3, minmax(0, 1fr));
      }
    }
  `,
})
export class DynamicFormComponent {
  readonly fields = input.required<FieldDef[]>();
  readonly sections = input<SectionDef[]>();
  readonly rules = input<Record<string, FieldRule>>();
  readonly initial = input<Record<string, unknown>>();
  readonly disabled = input(false);
  readonly serverError = input<ApiError | null>(null);

  readonly submitted = output<Record<string, unknown>>();

  /** Bumped on every value or status change of the live form. */
  private readonly tick = signal(0);
  private previous: { group: FormGroup; plan: ControlPlan[] } | null = null;

  private readonly plan = computed(() => planControls(this.fields(), this.rules()));

  readonly form = computed<FormGroup>(() => {
    const plan = this.plan();
    const initial = initialValues(plan, this.initial());
    const prev = this.previous;
    const group = new FormGroup<Record<string, FormControl>>({});
    for (const p of plan) {
      if (!p.rendered) {
        continue;
      }
      const old = prev?.group.controls[p.code];
      const oldField = prev?.plan.find((x) => x.code === p.code)?.field;
      const keep = old !== undefined && oldField !== undefined && oldField.type === p.field.type;
      const value = keep ? old.value : (initial[p.code] ?? null);
      const control = new FormControl(value, p.validators.map((k) => validatorFor(k, p.field)));
      if (p.disabled || this.disabled()) {
        control.disable({ emitEvent: false });
      }
      group.addControl(p.code, control, { emitEvent: false });
    }
    this.previous = { group, plan };
    return group;
  });

  private readonly renderedPlan = computed(() => this.plan().filter((p) => p.rendered));

  protected readonly sectionViews = computed<SectionView[]>(() => {
    const plan = this.plan();
    const group = this.form();
    const byCode = new Map(plan.map((p) => [p.code, p]));
    return layoutSections(plan, this.sections()).map((s) => ({
      ...(s.title !== undefined ? { title: s.title } : {}),
      columns: s.columns,
      items: s.fields.map((code) => ({
        plan: byCode.get(code) as ControlPlan,
        control: group.controls[code] as FormControl,
      })),
    }));
  });

  /** JSON-typed values of the submitted, non-empty controls of the live form. */
  readonly values = computed<Record<string, unknown>>(() => {
    this.tick();
    const group = this.form();
    const raw = group.getRawValue() as Record<string, unknown>;
    const converted: Record<string, unknown> = {};
    for (const p of this.renderedPlan()) {
      if (p.submitted && Object.prototype.hasOwnProperty.call(raw, p.code)) {
        converted[p.code] = toJsonValue(p.field.type, raw[p.code]);
      }
    }
    return collectValues(this.plan(), converted);
  });

  /** True when no enabled control is invalid. */
  readonly valid = computed<boolean>(() => {
    this.tick();
    return !Object.values(this.form().controls).some((c) => c.invalid);
  });

  /** Server violations split into per-control messages and the rest. */
  private readonly mapped = computed<MappedErrors | null>(() => {
    const err = this.serverError();
    if (!err) {
      return null;
    }
    return mapErrors(err, this.renderedPlan().map((p) => p.code));
  });

  /** Violation messages that matched no rendered control. */
  readonly unmatchedErrors = computed<string[]>(() => this.mapped()?.unmatched ?? []);

  /** The error shown in the form-level alert, or null when every violation sits on a control. */
  protected readonly alertError = computed<ApiError | null>(() => {
    const err = this.serverError();
    if (!err) {
      return null;
    }
    const hasViolations = (err.violations ?? []).length > 0;
    return !hasViolations || this.unmatchedErrors().length > 0 || err.status >= 500 || err.status === 0 ? err : null;
  });

  constructor() {
    effect((onCleanup) => {
      const group = this.form();
      const mapped = this.mapped();
      untracked(() => {
        if (!mapped) {
          return;
        }
        for (const [code, messages] of Object.entries(mapped.byControl)) {
          const control = group.controls[code];
          if (control && messages.length > 0) {
            control.setErrors({ ...(control.errors ?? {}), server: messages });
            control.markAsTouched();
          }
        }
      });
      onCleanup(() => {
        // Previous server error replaced or form rebuilt: drop only the server key.
        for (const control of Object.values(group.controls)) {
          if (control.errors && 'server' in control.errors) {
            const rest = { ...control.errors };
            delete rest['server'];
            control.setErrors(Object.keys(rest).length > 0 ? rest : null);
          }
        }
      });
    });

    effect((onCleanup) => {
      const group = this.form();
      const sub = group.valueChanges.subscribe(() => this.tick.update((n) => n + 1));
      const sub2 = group.statusChanges.subscribe(() => this.tick.update((n) => n + 1));
      onCleanup(() => {
        sub.unsubscribe();
        sub2.unsubscribe();
      });
    });
  }

  submit(): void {
    const group = this.form();
    group.markAllAsTouched();
    group.updateValueAndValidity();
    if (!this.valid()) {
      return;
    }
    this.submitted.emit(this.values());
  }
}
