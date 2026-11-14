import { describe, expect, it } from 'vitest';
import { parseStoredSession } from './session.service';

const NOW = 1_000_000;
const valid = { token: 't', expiresAt: NOW + 1000, userId: 'u1', displayName: 'Ann', roles: ['A', 'B'] };

describe('parseStoredSession', () => {
  it('accepts a valid unexpired session', () => {
    expect(parseStoredSession(JSON.stringify(valid), NOW)).toEqual(valid);
  });

  it('rejects an expired session (boundary included)', () => {
    expect(parseStoredSession(JSON.stringify({ ...valid, expiresAt: NOW }), NOW)).toBeNull();
    expect(parseStoredSession(JSON.stringify({ ...valid, expiresAt: NOW - 1 }), NOW)).toBeNull();
  });

  it('rejects malformed values without throwing', () => {
    for (const text of [null, undefined, '', '{', 'null', '[]', '42', '"x"', '{}']) {
      expect(parseStoredSession(text, NOW)).toBeNull();
    }
    expect(parseStoredSession(JSON.stringify({ ...valid, roles: [1] }), NOW)).toBeNull();
    expect(parseStoredSession(JSON.stringify({ ...valid, expiresAt: '5' }), NOW)).toBeNull();
    expect(parseStoredSession(JSON.stringify({ ...valid, token: '' }), NOW)).toBeNull();
  });
});
