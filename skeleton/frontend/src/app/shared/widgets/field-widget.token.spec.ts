import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FIELD_WIDGETS, WidgetRegistry } from './field-widget.token';

@Component({ selector: 'sk-test-a', template: '' })
class A {}
@Component({ selector: 'sk-test-b', template: '' })
class B {}

function registry(regs: { key: string; component: unknown }[]): WidgetRegistry {
  TestBed.configureTestingModule({
    providers: regs.map((r) => ({ provide: FIELD_WIDGETS, multi: true, useValue: r })),
  });
  return TestBed.inject(WidgetRegistry);
}

describe('WidgetRegistry', () => {
  afterEach(() => vi.restoreAllMocks());

  it('keeps the first registration for a key', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const r = registry([
      { key: 'qr', component: A },
      { key: 'qr', component: B },
    ]);
    expect(r.get('qr')).toBe(A);
  });

  it('logs console.error naming each duplicate key', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    registry([
      { key: 'qr', component: A },
      { key: 'qr', component: B },
      { key: 'map', component: A },
      { key: 'map', component: B },
    ]);
    expect(spy).toHaveBeenCalledTimes(2);
    expect(String(spy.mock.calls[0][0])).toContain('qr');
    expect(String(spy.mock.calls[1][0])).toContain('map');
  });

  it('returns undefined for unknown, empty or missing keys and does not log', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const r = registry([{ key: 'qr', component: A }]);
    expect(r.get('nope')).toBeUndefined();
    expect(r.get('')).toBeUndefined();
    expect(r.get(undefined)).toBeUndefined();
    expect(spy).not.toHaveBeenCalled();
  });

  it('works with no registrations', () => {
    TestBed.configureTestingModule({});
    expect(TestBed.inject(WidgetRegistry).get('qr')).toBeUndefined();
  });
});
