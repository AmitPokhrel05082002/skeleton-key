import { describe, expect, it } from 'vitest';
import { resolveDefaultApp, validateRuntimeConfig } from './host-resolver';

describe('resolveDefaultApp', () => {
  const hosts = { 'grievance.example.org': 'GRIEVANCE', 'assets.example.org': 'ASSETS' };

  it('returns the app code for an exact host match', () => {
    expect(resolveDefaultApp(hosts, 'assets.example.org')).toBe('ASSETS');
  });

  it('matches case-insensitively on upper-case input', () => {
    expect(resolveDefaultApp(hosts, 'GRIEVANCE.Example.ORG')).toBe('GRIEVANCE');
  });

  it('matches upper-case mapping keys', () => {
    expect(resolveDefaultApp({ 'Assets.Example.org': 'ASSETS' }, 'assets.example.org')).toBe('ASSETS');
  });

  it('ignores the port in the host', () => {
    expect(resolveDefaultApp(hosts, 'assets.example.org:8080')).toBe('ASSETS');
  });

  it('returns null when no host matches', () => {
    expect(resolveDefaultApp(hosts, 'other.example.org')).toBeNull();
  });

  it('returns null for an empty mapping', () => {
    expect(resolveDefaultApp({}, 'assets.example.org')).toBeNull();
  });

  it('returns null for an empty host', () => {
    expect(resolveDefaultApp(hosts, '')).toBeNull();
  });

  it('does not resolve inherited object properties', () => {
    expect(resolveDefaultApp({}, 'constructor')).toBeNull();
  });
});

describe('validateRuntimeConfig', () => {
  it('accepts a config with hosts and apiBasePath', () => {
    expect(validateRuntimeConfig({ apiBasePath: '/api', hosts: { 'a.org': 'A' } })).toBe(true);
  });

  it('accepts a config with an empty hosts mapping and no apiBasePath', () => {
    expect(validateRuntimeConfig({ hosts: {} })).toBe(true);
  });

  it('rejects non-object values', () => {
    expect(validateRuntimeConfig(null)).toBe(false);
    expect(validateRuntimeConfig(undefined)).toBe(false);
    expect(validateRuntimeConfig('x')).toBe(false);
    expect(validateRuntimeConfig([])).toBe(false);
  });

  it('rejects a missing or non-object hosts', () => {
    expect(validateRuntimeConfig({})).toBe(false);
    expect(validateRuntimeConfig({ hosts: [] })).toBe(false);
    expect(validateRuntimeConfig({ hosts: 'a' })).toBe(false);
  });

  it('rejects non-string host values', () => {
    expect(validateRuntimeConfig({ hosts: { 'a.org': 1 } })).toBe(false);
  });

  it('rejects a non-string apiBasePath', () => {
    expect(validateRuntimeConfig({ apiBasePath: 5, hosts: {} })).toBe(false);
  });
});
