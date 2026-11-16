import { describe, expect, it } from 'vitest';
import { formatDuration } from './duration';

describe('formatDuration', () => {
  it('formats hours and minutes', () => {
    expect(formatDuration(2 * 3600_000 + 5 * 60_000)).toBe('2h 5m');
  });

  it('formats days, minutes and seconds', () => {
    expect(formatDuration(86400_000 + 3 * 3600_000 + 59_000)).toBe('1d 3h');
    expect(formatDuration(4 * 60_000 + 10_000)).toBe('4m 10s');
    expect(formatDuration(12_000)).toBe('12s');
  });

  it('gives 0s for zero, negative, tiny or invalid values', () => {
    expect(formatDuration(0)).toBe('0s');
    expect(formatDuration(-5)).toBe('0s');
    expect(formatDuration(999)).toBe('0s');
    expect(formatDuration(Number.NaN)).toBe('0s');
    expect(formatDuration(undefined)).toBe('0s');
  });
});
