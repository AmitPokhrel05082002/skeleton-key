import type { FieldDef, FieldRule } from '../api/api.types';

/**
 * Effective rule of a field: the entry of the rule map when one is supplied and holds the code
 * (own property only), otherwise `required` for `required: true` fields and `optional` for the rest.
 */
export function effectiveRule(f: FieldDef, rules?: Record<string, FieldRule>): FieldRule {
  if (rules && Object.prototype.hasOwnProperty.call(rules, f.code)) {
    return rules[f.code];
  }
  return f.required === true ? 'required' : 'optional';
}
