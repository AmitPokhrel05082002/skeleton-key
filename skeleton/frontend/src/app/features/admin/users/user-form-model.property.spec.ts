import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';
import type { UpdateUserRequest } from '../../../core/api/api.types';
import { toPatch, validateUserForm } from './user-form-model';

interface Editable {
  displayName: string;
  roles: string[];
  active: boolean;
}

const roleCode = fc.constantFrom('A', 'B', 'C', 'D', 'E');
const editable = fc.record({
  displayName: fc.string({ minLength: 1, maxLength: 20 }),
  roles: fc.uniqueArray(roleCode),
  active: fc.boolean(),
});

function apply(original: Editable, patch: UpdateUserRequest): Editable {
  return {
    displayName: patch.displayName ?? original.displayName,
    roles: patch.roles ?? original.roles,
    active: patch.active ?? original.active,
  };
}

const asSet = (r: string[]) => [...new Set(r)].sort();

// Feature: user-admin-and-tracking, Property 14 (patch part)
describe('toPatch (Property 14)', () => {
  it('is empty iff nothing changed, roles compared as sets', () => {
    fc.assert(
      fc.property(editable, editable, (original, edited) => {
        const patch = toPatch(original, edited);
        const unchanged =
          original.displayName === edited.displayName &&
          original.active === edited.active &&
          JSON.stringify(asSet(original.roles)) === JSON.stringify(asSet(edited.roles));
        expect(Object.keys(patch).length === 0).toBe(unchanged);
      }),
    );
  });

  it('applied to the original gives the edited values', () => {
    fc.assert(
      fc.property(editable, editable, (original, edited) => {
        const result = apply(original, toPatch(original, edited));
        expect(result.displayName).toBe(edited.displayName);
        expect(result.active).toBe(edited.active);
        expect(asSet(result.roles)).toEqual(asSet(edited.roles));
      }),
    );
  });

  it('role order alone is not a change', () => {
    fc.assert(
      fc.property(editable, (original) => {
        const shuffled = { ...original, roles: [...original.roles].reverse() };
        expect(toPatch(original, shuffled)).toEqual({});
      }),
    );
  });
});

describe('validateUserForm', () => {
  const base = { username: 'asha', displayName: 'Asha', password: 'longenough', roles: [], active: true };

  it('accepts a valid create form', () => {
    expect(validateUserForm(base, 'create')).toEqual({});
  });

  it('rejects usernames with whitespace or bad length, passwords outside 8..72 bytes', () => {
    expect(validateUserForm({ ...base, username: 'a b c' }, 'create').username).toBeTruthy();
    expect(validateUserForm({ ...base, username: 'ab' }, 'create').username).toBeTruthy();
    expect(validateUserForm({ ...base, username: 'x'.repeat(65) }, 'create').username).toBeTruthy();
    expect(validateUserForm({ ...base, password: 'short' }, 'create').password).toBeTruthy();
    expect(validateUserForm({ ...base, password: 'x'.repeat(73) }, 'create').password).toBeTruthy();
    // 37 two-byte characters = 74 bytes
    expect(validateUserForm({ ...base, password: 'é'.repeat(37) }, 'create').password).toBeTruthy();
    expect(validateUserForm({ ...base, password: 'x'.repeat(72) }, 'create').password).toBeUndefined();
  });

  it('ignores username and password in edit mode but checks the display name', () => {
    expect(validateUserForm({ ...base, username: '', password: '' }, 'edit')).toEqual({});
    expect(validateUserForm({ ...base, displayName: '  ' }, 'edit').displayName).toBeTruthy();
    expect(validateUserForm({ ...base, displayName: 'x'.repeat(121) }, 'edit').displayName).toBeTruthy();
  });
});
