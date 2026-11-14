import { describe, expect, it } from 'vitest';
import { slaState, WARNING_WINDOW_MS } from './sla';
import { safeLogoUrl } from './safe-url';
import { errorText } from './error-text';

describe('slaState', () => {
  const now = new Date('2026-01-10T12:00:00Z');
  it('classifies overdue, due soon and none', () => {
    expect(slaState('2026-01-10T11:59:59Z', now, WARNING_WINDOW_MS, false)).toBe('OVERDUE');
    expect(slaState('2026-01-10T12:00:00Z', now, WARNING_WINDOW_MS, false)).toBe('DUE_SOON');
    expect(slaState('2026-01-11T12:00:00Z', now, WARNING_WINDOW_MS, false)).toBe('DUE_SOON');
    expect(slaState('2026-01-11T12:00:01Z', now, WARNING_WINDOW_MS, false)).toBe('NONE');
  });
  it('gives NONE for ended, missing or invalid dates', () => {
    expect(slaState('2026-01-01T00:00:00Z', now, WARNING_WINDOW_MS, true)).toBe('NONE');
    expect(slaState(null, now, WARNING_WINDOW_MS, false)).toBe('NONE');
    expect(slaState(undefined, now, WARNING_WINDOW_MS, false)).toBe('NONE');
    expect(slaState('nope', now, WARNING_WINDOW_MS, false)).toBe('NONE');
  });
});

describe('safeLogoUrl', () => {
  it('accepts http, https and same-origin relative paths', () => {
    expect(safeLogoUrl('https://x.test/a.png')).toBe('https://x.test/a.png');
    expect(safeLogoUrl('HTTP://x.test/a.png')).toBe('HTTP://x.test/a.png');
    expect(safeLogoUrl('/branding/logo.svg')).toBe('/branding/logo.svg');
    expect(safeLogoUrl('logo.svg')).toBe('logo.svg');
  });
  it('rejects other schemes, protocol-relative and empty values', () => {
    for (const bad of ['javascript:alert(1)', ' data:image/png;base64,AA', 'ftp://x', '//evil.test/a.png', '\\\\evil', '', '  ', 'java\nscript:x']) {
      expect(safeLogoUrl(bad)).toBeNull();
    }
    expect(safeLogoUrl(null)).toBeNull();
  });
});

describe('errorText', () => {
  const base = { code: 'X', violations: [] };
  it('hides the server message for 5xx and shows the requestId', () => {
    const text = errorText({ ...base, status: 500, message: 'NPE at Foo.java:12', requestId: 'req-1' });
    expect(text).toContain('req-1');
    expect(text).not.toContain('NPE');
    expect(errorText({ ...base, status: 503, message: 'secret' })).not.toContain('secret');
  });
  it('returns the message for non-5xx', () => {
    expect(errorText({ ...base, status: 409, message: 'Conflict' })).toBe('Conflict');
  });
});
