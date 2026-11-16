import type { InputSignal, Type } from '@angular/core';
import type { FormControl } from '@angular/forms';
import type { FieldDef, FieldRule } from '../../core/api/api.types';

/**
 * Contract a custom field widget component implements. A widget is a standalone
 * component that declares these signal inputs; the Dynamic_Form binds them.
 */
export interface FieldWidgetComponent {
  readonly field: InputSignal<FieldDef>;
  readonly control: InputSignal<FormControl>;
  readonly rule: InputSignal<FieldRule>;
  readonly disabled: InputSignal<boolean>;
}

/** One registration provided through the FIELD_WIDGETS multi token. */
export interface FieldWidgetRegistration {
  /** Unique widget key, matched against the `widget` string of a field definition. */
  key: string;
  /** Standalone component type implementing {@link FieldWidgetComponent}. */
  component: Type<unknown>;
}
