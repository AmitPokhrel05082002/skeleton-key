import { describe, expect, it } from 'vitest';
import { actionDialogReady, buildActionBody } from './action-helpers';

describe('actionDialogReady', () => {
  it('requires non-blank remarks and attachment only when required', () => {
    const a = { remarks: 'required', attachment: 'optional' } as const;
    expect(actionDialogReady(a, '', '')).toBe(false);
    expect(actionDialogReady(a, '   ', 'x')).toBe(false);
    expect(actionDialogReady(a, 'ok', '')).toBe(true);
    const b = { remarks: 'hidden', attachment: 'required' } as const;
    expect(actionDialogReady(b, '', ' ')).toBe(false);
    expect(actionDialogReady(b, '', 'ref')).toBe(true);
  });
});

describe('buildActionBody', () => {
  const rules = { a: 'optional', b: 'required', c: 'readonly', d: 'hidden' } as const;

  it('includes only changed editable fields', () => {
    const body = buildActionBody(
      { a: 1, b: 'x', c: 1, d: 1 },
      { a: 2, b: 'x', c: 2, d: 2, e: 5 },
      { ...rules },
    );
    expect(body).toEqual({ fields: { a: 2 } });
  });

  it('compares arrays deeply and treats missing initial as changed', () => {
    const body = buildActionBody({ a: ['x'] }, { a: ['x'], b: 'new' }, { ...rules });
    expect(body.fields).toEqual({ b: 'new' });
  });

  it('adds remarks and attachment only when non-blank', () => {
    expect(buildActionBody({}, {}, {}, ' ', '')).toEqual({ fields: {} });
    expect(buildActionBody({}, {}, {}, 'r', 'ref')).toEqual({ fields: {}, remarks: 'r', attachment: 'ref' });
  });

  it('sends null for a cleared field (missing, empty string or empty array)', () => {
    const r = { a: 'optional', b: 'optional', c: 'required' } as const;
    const body = buildActionBody({ a: 'x', b: 'y', c: ['z'] }, { b: '', c: [] }, { ...r });
    expect(body.fields).toEqual({ a: null, b: null, c: null });
  });

  it('does not send a field that is empty before and after', () => {
    const r = { a: 'optional', b: 'optional', c: 'optional' } as const;
    const body = buildActionBody({ a: '', c: null }, { a: '', b: [] }, { ...r });
    expect(body.fields).toEqual({});
  });

  it('never sends readonly or hidden fields, changed or cleared', () => {
    const r = { c: 'readonly', d: 'hidden' } as const;
    expect(buildActionBody({ c: 1, d: 1 }, { c: 2, d: 2 }, { ...r }).fields).toEqual({});
    expect(buildActionBody({ c: 1, d: 1 }, {}, { ...r }).fields).toEqual({});
  });

  it('still sends a changed non-empty value', () => {
    expect(buildActionBody({ a: 'x' }, { a: 'y' }, { a: 'optional' }).fields).toEqual({ a: 'y' });
  });
});
