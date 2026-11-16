import { describe, expect, it } from 'vitest';
import type { ApiError } from '../api/api.types';
import { mapErrors, violationMessage } from './error-mapper';

const err = (violations: ApiError['violations']): ApiError => ({
  status: 400,
  code: 'VALIDATION',
  message: 'bad',
  violations,
});

describe('error-mapper', () => {
  it('assigns matching violations to controls in order and the rest to unmatched', () => {
    const r = mapErrors(
      err([
        { field: 'a', rule: 'x', message: 'one' },
        { field: 'zzz', rule: 'x', message: 'lost' },
        { field: 'a', rule: 'x', message: 'two' },
      ]),
      ['a', 'b'],
    );
    expect(r.byControl['a']).toEqual(['one', 'two']);
    expect(r.byControl['b']).toEqual([]);
    expect(r.unmatched).toEqual(['lost']);
  });

  it('does not treat prototype names as controls', () => {
    const r = mapErrors(err([{ field: 'constructor', rule: 'x', message: 'm' }]), ['a']);
    expect(r.unmatched).toEqual(['m']);
  });

  it('builds messages: message, lookup, rule text, fallback', () => {
    expect(violationMessage({ field: 'f', rule: 'required', message: 'Custom' })).toBe('Custom');
    expect(violationMessage({ field: 'f', rule: 'required' })).toBe('This field is required');
    expect(violationMessage({ field: 'f', rule: 'weird_rule', message: '  ' })).toBe('weird_rule');
    expect(violationMessage({ field: 'f', rule: '' })).toBe('Invalid value');
    expect(violationMessage({ field: 'f', rule: 'toString' })).toBe('toString');
  });
});
