import { Injectable, InjectionToken, Type, inject } from '@angular/core';
import type { FieldWidgetRegistration } from './field-widget.types';

/** Multi token: apps register custom field widgets with `{ provide: FIELD_WIDGETS, multi: true, useValue }`. */
export const FIELD_WIDGETS = new InjectionToken<FieldWidgetRegistration[]>('FIELD_WIDGETS');

/**
 * Builds a key to component map from registrations. The first registration per key
 * wins; each later duplicate logs a console.error naming the key.
 */
export function buildWidgetMap(
  registrations: readonly FieldWidgetRegistration[],
): Map<string, Type<unknown>> {
  const map = new Map<string, Type<unknown>>();
  for (const reg of registrations) {
    if (map.has(reg.key)) {
      console.error(`FIELD_WIDGETS: duplicate widget key "${reg.key}"; using the first registration.`);
      continue;
    }
    map.set(reg.key, reg.component);
  }
  return map;
}

@Injectable({ providedIn: 'root' })
export class WidgetRegistry {
  private readonly widgets = buildWidgetMap(
    (inject(FIELD_WIDGETS, { optional: true }) as FieldWidgetRegistration[] | null) ?? [],
  );

  /** Returns the component for a key, or undefined when the key is missing or unregistered. */
  get(key: string | null | undefined): Type<unknown> | undefined {
    if (typeof key !== 'string' || key === '') return undefined;
    return this.widgets.get(key);
  }
}
