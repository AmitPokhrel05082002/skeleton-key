import { NgComponentOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormControl, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { NzDatePickerModule } from 'ng-zorro-antd/date-picker';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { WidgetRegistry } from '../../shared/widgets/field-widget.token';
import type { FieldDef, FieldRule } from './form-model';

export const FILE_UNAVAILABLE_HINT = 'File upload is not yet available';

/** Short message for one error entry of a control. Unknown keys give no message. */
export function errorMessages(errors: Record<string, unknown> | null): string[] {
  if (!errors) {
    return [];
  }
  const out: string[] = [];
  for (const [key, value] of Object.entries(errors)) {
    const v = (value ?? {}) as Record<string, unknown>;
    switch (key) {
      case 'required':
        out.push('This field is required');
        break;
      case 'maxLength':
      case 'maxlength':
        out.push(`Must be at most ${String(v['requiredLength'] ?? '')} characters`);
        break;
      case 'min':
        out.push(`Must be at least ${String(v['min'] ?? '')}`);
        break;
      case 'max':
        out.push(`Must be at most ${String(v['max'] ?? '')}`);
        break;
      case 'pattern':
        out.push('Invalid format');
        break;
      case 'email':
        out.push('Enter a valid email address');
        break;
      case 'server':
        if (Array.isArray(value)) {
          out.push(...value.map((m) => String(m)));
        } else if (typeof value === 'string') {
          out.push(value);
        }
        break;
      default:
        break;
    }
  }
  return out;
}

@Component({
  selector: 'sk-field-control',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgComponentOutlet,
    FormsModule,
    ReactiveFormsModule,
    NzFormModule,
    NzInputModule,
    NzInputNumberModule,
    NzDatePickerModule,
    NzSelectModule,
    NzSwitchModule,
  ],
  template: `
    @if (widget(); as w) {
      <ng-container [ngComponentOutlet]="w" [ngComponentOutletInputs]="widgetInputs()" />
    } @else {
      <nz-form-item>
        <nz-form-label [nzFor]="inputId()" [nzRequired]="rule() === 'required'">{{ field().label }}</nz-form-label>
        <nz-form-control [nzValidateStatus]="invalid() ? 'error' : ''" [nzExtra]="field().help ?? ''">
          @switch (field().type) {
            @case ('TEXTAREA') {
              <textarea
                nz-input
                rows="3"
                [id]="inputId()"
                [formControl]="control()"
                [attr.aria-invalid]="invalid() ? 'true' : null"
                [attr.aria-describedby]="invalid() ? errorId() : null"
              ></textarea>
            }
            @case ('NUMBER') {
              <nz-input-number
                [id]="inputId()"
                class="sk-full"
                [nzPrecision]="0"
                [formControl]="control()"
                [attr.aria-label]="field().label"
                [attr.aria-invalid]="invalid() ? 'true' : null"
                [attr.aria-describedby]="invalid() ? errorId() : null"
              />
            }
            @case ('DECIMAL') {
              <nz-input-number
                [id]="inputId()"
                class="sk-full"
                [nzStep]="0.01"
                [formControl]="control()"
                [attr.aria-label]="field().label"
                [attr.aria-invalid]="invalid() ? 'true' : null"
                [attr.aria-describedby]="invalid() ? errorId() : null"
              />
            }
            @case ('DATE') {
              <nz-date-picker
                [id]="inputId()"
                class="sk-full"
                [formControl]="control()"
                [attr.aria-label]="field().label"
                [attr.aria-invalid]="invalid() ? 'true' : null"
                [attr.aria-describedby]="invalid() ? errorId() : null"
              />
            }
            @case ('DATETIME') {
              <nz-date-picker
                [id]="inputId()"
                class="sk-full"
                nzShowTime
                [formControl]="control()"
                [attr.aria-label]="field().label"
                [attr.aria-invalid]="invalid() ? 'true' : null"
                [attr.aria-describedby]="invalid() ? errorId() : null"
              />
            }
            @case ('BOOLEAN') {
              <nz-switch
                [nzId]="inputId()"
                [formControl]="control()"
                [attr.aria-label]="field().label"
                [attr.aria-invalid]="invalid() ? 'true' : null"
                [attr.aria-describedby]="invalid() ? errorId() : null"
              />
            }
            @case ('SELECT') {
              <nz-select
                [nzId]="inputId()"
                class="sk-full"
                nzAllowClear
                [formControl]="control()"
                [attr.aria-label]="field().label"
                [attr.aria-invalid]="invalid() ? 'true' : null"
                [attr.aria-describedby]="invalid() ? errorId() : null"
              >
                @for (o of field().options ?? []; track o.value) {
                  <nz-option [nzValue]="o.value" [nzLabel]="o.label" />
                }
              </nz-select>
            }
            @case ('MULTISELECT') {
              <nz-select
                [nzId]="inputId()"
                class="sk-full"
                nzMode="multiple"
                [formControl]="control()"
                [attr.aria-label]="field().label"
                [attr.aria-invalid]="invalid() ? 'true' : null"
                [attr.aria-describedby]="invalid() ? errorId() : null"
              >
                @for (o of field().options ?? []; track o.value) {
                  <nz-option [nzValue]="o.value" [nzLabel]="o.label" />
                }
              </nz-select>
            }
            @case ('FILE') {
              <input
                nz-input
                disabled
                [id]="inputId()"
                [attr.aria-label]="field().label"
                [attr.aria-describedby]="errorId() + ' ' + inputId() + '-hint'"
              />
              <div class="sk-hint" [id]="inputId() + '-hint'">{{ fileHint }}</div>
            }
            @case ('LOCATION') {
              <div class="sk-location" [id]="inputId()" role="group" [attr.aria-label]="field().label">
                <nz-input-number
                  class="sk-full"
                  [ngModel]="location().lat"
                  [ngModelOptions]="{ standalone: true }"
                  [nzDisabled]="isDisabled()"
                  nzPlaceholder="Latitude"
                  (ngModelChange)="setLocation('lat', $event)"
                  [attr.aria-label]="field().label + ' latitude'"
                  [attr.aria-invalid]="invalid() ? 'true' : null"
                  [attr.aria-describedby]="invalid() ? errorId() : null"
                />
                <nz-input-number
                  class="sk-full"
                  [ngModel]="location().lng"
                  [ngModelOptions]="{ standalone: true }"
                  [nzDisabled]="isDisabled()"
                  nzPlaceholder="Longitude"
                  (ngModelChange)="setLocation('lng', $event)"
                  [attr.aria-label]="field().label + ' longitude'"
                  [attr.aria-invalid]="invalid() ? 'true' : null"
                  [attr.aria-describedby]="invalid() ? errorId() : null"
                />
              </div>
            }
            @default {
              <input
                nz-input
                [type]="htmlType()"
                [id]="inputId()"
                [formControl]="control()"
                [attr.aria-invalid]="invalid() ? 'true' : null"
                [attr.aria-describedby]="invalid() ? errorId() : null"
              />
            }
          }
          @if (messages().length > 0) {
            <div class="sk-error" role="alert" [id]="errorId()">
              @for (m of messages(); track $index) {
                <div>{{ m }}</div>
              }
            </div>
          }
        </nz-form-control>
      </nz-form-item>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .sk-full {
      width: 100%;
    }
    .sk-location {
      display: grid;
      gap: 8px;
      grid-template-columns: 1fr 1fr;
    }
    .sk-hint {
      color: rgba(0, 0, 0, 0.55);
      font-size: 12px;
      margin-top: 4px;
    }
    .sk-error {
      color: #cf1322;
      font-size: 14px;
    }
  `,
})
export class FieldControlComponent {
  readonly field = input.required<FieldDef>();
  readonly control = input.required<FormControl>();
  readonly rule = input.required<FieldRule>();
  readonly disabled = input(false);

  protected readonly fileHint = FILE_UNAVAILABLE_HINT;
  private readonly registry = inject(WidgetRegistry);

  /** Bumped on every control event (value, status, touched, pristine). */
  private readonly version = signal(0);

  constructor() {
    effect((onCleanup) => {
      const sub = this.control().events.subscribe(() => this.version.update((n) => n + 1));
      this.version.update((n) => n + 1);
      onCleanup(() => sub.unsubscribe());
    });
  }

  protected readonly widget = computed(() => this.registry.get(this.field().widget));

  protected readonly widgetInputs = computed<Record<string, unknown>>(() => ({
    field: this.field(),
    control: this.control(),
    rule: this.rule(),
    disabled: this.disabled(),
  }));

  protected readonly inputId = computed(() => `sk-f-${this.field().code}`);
  protected readonly errorId = computed(() => `${this.field().code}-error`);

  protected readonly messages = computed<string[]>(() => {
    this.version();
    const c = this.control();
    const errors = c.errors;
    if (!errors) {
      return [];
    }
    const shown = c.touched || c.dirty || errors['server'] !== undefined;
    return shown ? errorMessages(errors) : [];
  });

  protected readonly invalid = computed(() => this.messages().length > 0);

  protected readonly isDisabled = computed(() => {
    this.version();
    return this.disabled() || this.control().disabled;
  });

  protected readonly htmlType = computed(() => {
    switch (this.field().type) {
      case 'EMAIL':
        return 'email';
      case 'PHONE':
        return 'tel';
      default:
        return 'text';
    }
  });

  protected readonly location = computed<{ lat: number | null; lng: number | null }>(() => {
    this.version();
    const v = this.control().value as { lat?: unknown; lng?: unknown } | null;
    const num = (x: unknown): number | null => (typeof x === 'number' && Number.isFinite(x) ? x : null);
    return { lat: num(v?.lat), lng: num(v?.lng) };
  });

  protected setLocation(part: 'lat' | 'lng', value: number | null): void {
    const next = { ...this.location(), [part]: value ?? null };
    const c = this.control();
    c.setValue(next.lat === null && next.lng === null ? null : next);
    c.markAsDirty();
  }
}
