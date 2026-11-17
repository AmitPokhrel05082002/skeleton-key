import { describe, expect, it } from 'vitest';
import type { VersionRef } from '../../core/api/api.types';
import {
  canPublish,
  chooseSave,
  formatJson,
  jsonState,
  lineColumnToOffset,
  minimalDefinitionTemplate,
  pointerToOffset,
} from './json-editor-helpers';

const ref = (status: 'DRAFT' | 'PUBLISHED'): VersionRef => ({ id: 'i', version: 1, status, contentHash: 'h' });

describe('formatJson / jsonState', () => {
  it('indents with two spaces and round-trips', () => {
    const v = { a: [1, { b: null }], c: 'x' };
    const text = formatJson(v);
    expect(text).toContain('\n  "a": [');
    expect(JSON.parse(text)).toEqual(v);
  });

  it('reports validity exactly like JSON.parse', () => {
    expect(jsonState('{"a":1}')).toEqual({ valid: true, value: { a: 1 } });
    const bad = jsonState('{"a":');
    expect(bad.valid).toBe(false);
    expect(bad.error).toBeTruthy();
    expect(jsonState('').valid).toBe(false);
  });
});

describe('pointerToOffset', () => {
  const text = formatJson({
    app: { code: 'A' },
    roles: [{ code: 'R1' }, { code: 'R2' }],
    workflows: [
      {
        code: 'WF',
        steps: [{ code: 'S1', actions: [{ code: 'GO' }] }],
        transitions: [{ from: 'S1', to: 'S1' }],
      },
    ],
    'a/b': { 'c~d': 7 },
  });

  it('resolves JSON Pointers to the start of the value', () => {
    expect(pointerToOffset(text, '')).toBe(0);
    expect(text.slice(pointerToOffset(text, '/app/code')!)).toMatch(/^"A"/);
    expect(text.slice(pointerToOffset(text, '/roles/1')!)).toMatch(/^\{\s+"code": "R2"/);
    expect(text.slice(pointerToOffset(text, '/a~1b/c~0d')!)).toMatch(/^7/);
  });

  it('resolves the backend validator code paths', () => {
    expect(text.slice(pointerToOffset(text, 'roles[R2]')!)).toMatch(/^\{\s+"code": "R2"/);
    expect(text.slice(pointerToOffset(text, 'workflows[WF].steps[S1].actions[GO]')!)).toMatch(/^\{\s+"code": "GO"/);
    expect(text.slice(pointerToOffset(text, 'workflows[WF].transitions[0]')!)).toMatch(/^\{\s+"from": "S1"/);
    expect(text.slice(pointerToOffset(text, 'workflows[WF].transitions[S1]')!)).toMatch(/^\{\s+"from": "S1"/);
    expect(text.slice(pointerToOffset(text, 'workflows[WF]')!)).toMatch(/^\{\s+"code": "WF"/);
  });

  it('returns null for unknown paths, malformed paths and malformed text, without throwing', () => {
    expect(pointerToOffset(text, '/nope')).toBeNull();
    expect(pointerToOffset(text, '/roles/9')).toBeNull();
    expect(pointerToOffset(text, 'roles[NOPE]')).toBeNull();
    expect(pointerToOffset(text, 'roles[')).toBeNull();
    expect(pointerToOffset(text, '/app/code/deeper')).toBeNull();
    expect(pointerToOffset('{"a":', '/a')).toBeNull();
    expect(pointerToOffset('', '')).toBeNull();
    expect(pointerToOffset('[' .repeat(5000), '/0')).toBeNull();
  });
});

describe('lineColumnToOffset', () => {
  const text = 'ab\ncde\n\nf';
  it('is 1-based', () => {
    expect(lineColumnToOffset(text, 1, 1)).toBe(0);
    expect(lineColumnToOffset(text, 2, 2)).toBe(4);
    expect(lineColumnToOffset(text, 4, 1)).toBe(8);
  });
  it('clamps', () => {
    expect(lineColumnToOffset(text, 1, 99)).toBe(2);
    expect(lineColumnToOffset(text, 99, 1)).toBe(text.length);
    expect(lineColumnToOffset(text, 0, 0)).toBe(0);
    expect(lineColumnToOffset(text, NaN, -5)).toBe(0);
    expect(lineColumnToOffset('', 3, 3)).toBe(0);
  });
  it('agrees with pointerToOffset on formatted text', () => {
    const t = formatJson({ a: { b: 1 } });
    const off = pointerToOffset(t, '/a/b')!;
    const before = t.slice(0, off).split('\n');
    expect(lineColumnToOffset(t, before.length, before[before.length - 1].length + 1)).toBe(off);
  });
});

describe('chooseSave / canPublish', () => {
  it('chooses the endpoint from the saved version', () => {
    expect(chooseSave({ saved: null, dirty: true })).toBe('POST');
    expect(chooseSave({ saved: ref('DRAFT'), dirty: true })).toBe('PUT');
    expect(chooseSave({ saved: ref('PUBLISHED'), dirty: true })).toBe('EDIT');
  });
  it('publishes only a clean saved DRAFT', () => {
    expect(canPublish({ saved: ref('DRAFT'), dirty: false })).toBe(true);
    expect(canPublish({ saved: ref('DRAFT'), dirty: true })).toBe(false);
    expect(canPublish({ saved: ref('PUBLISHED'), dirty: false })).toBe(false);
    expect(canPublish({ saved: null, dirty: false })).toBe(false);
  });
});

describe('minimalDefinitionTemplate', () => {
  interface Step {
    code: string;
    type: string;
    outcome?: string;
    assignee: { role: string };
    actions: { roles: string[]; to: string }[];
  }
  const t = minimalDefinitionTemplate() as unknown as {
    schemaVersion: number;
    app: object;
    roles: { code: string }[];
    entities: { code: string; fields: object[] }[];
    workflows: {
      entity: string;
      startStep: string;
      initialStatus: string;
      statuses: { label: string }[];
      steps: Step[];
    }[];
  };

  it('has the schema required keys at every level', () => {
    for (const k of ['schemaVersion', 'app', 'roles', 'entities', 'workflows']) expect(t).toHaveProperty(k);
    expect(t.schemaVersion).toBe(1);
    for (const k of ['code', 'name']) expect(t.app).toHaveProperty(k);
    for (const k of ['code', 'label']) expect(t.roles[0]).toHaveProperty(k);
    for (const k of ['code', 'label', 'fields']) expect(t.entities[0]).toHaveProperty(k);
    for (const k of ['code', 'label', 'type']) expect(t.entities[0].fields[0]).toHaveProperty(k);
    const wf = t.workflows[0];
    for (const k of ['code', 'entity', 'startStep', 'statuses', 'steps']) expect(wf).toHaveProperty(k);
    expect(wf.steps.length).toBeGreaterThanOrEqual(2);
    for (const k of ['code', 'type', 'assignee', 'actions']) expect(wf.steps[0]).toHaveProperty(k);
    for (const k of ['code', 'label', 'roles']) expect(wf.steps[0].actions[0]).toHaveProperty(k);
    for (const k of ['code', 'type', 'outcome']) expect(wf.steps[1]).toHaveProperty(k);
    expect(wf.steps[1]).not.toHaveProperty('actions');
  });

  it('satisfies the cross-reference rules', () => {
    const wf = t.workflows[0];
    const roles = t.roles.map((r) => r.code);
    const stepCodes = wf.steps.map((s) => s.code);
    expect(wf.entity).toBe(t.entities[0].code);
    expect(stepCodes).toContain(wf.startStep);
    expect(roles).toContain(wf.steps[0].assignee.role);
    expect(wf.steps[0].actions[0].roles.every((r: string) => roles.includes(r))).toBe(true);
    expect(stepCodes).toContain(wf.steps[0].actions[0].to);
    expect(wf.steps.find((s) => s.code === wf.steps[0].actions[0].to)?.type).toBe('END');
    const settable = new Set<string | undefined>([wf.initialStatus, wf.steps[1].outcome]);
    expect(wf.statuses.every((s) => settable.has(s.label))).toBe(true);
  });

  it('returns a fresh object each call', () => {
    expect(minimalDefinitionTemplate()).not.toBe(minimalDefinitionTemplate());
  });
});
