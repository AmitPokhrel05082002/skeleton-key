import { describe, expect, it } from 'vitest';
import { contrastRatio, onColor } from '../pure/contrast';
import { ACCENT, THEME_TOKEN_PAIRS } from './theme-tokens';

const SAMPLE_PRIMARIES = [
  '#000000', '#ffffff', '#ff7a1a', '#1890ff', '#0b5394', '#fadb14',
  '#52c41a', '#f5222d', '#722ed1', '#13c2c2', '#777777', '#eb2f96',
];

describe('theme tokens contrast (Requirements 6.4, 6.8)', () => {
  it('covers both the dark and the light theme', () => {
    const themes = new Set(THEME_TOKEN_PAIRS.map((p) => p.theme));
    expect(themes.has('dark')).toBe(true);
    expect(themes.has('light')).toBe(true);
  });

  it('includes the orange accent as a checked pair', () => {
    const accentPairs = THEME_TOKEN_PAIRS.filter(
      (p) => p.fg.toLowerCase() === ACCENT || p.bg.toLowerCase() === ACCENT,
    );
    expect(accentPairs.length).toBeGreaterThan(0);
    expect(new Set(accentPairs.map((p) => p.theme))).toEqual(new Set(['dark', 'light']));
  });

  it('declares only the 4.5 and 3 minimums', () => {
    for (const p of THEME_TOKEN_PAIRS) {
      expect([4.5, 3], p.name).toContain(p.min);
    }
  });

  describe.each(THEME_TOKEN_PAIRS.map((p) => [p.name, p] as const))('%s', (_name, pair) => {
    it('meets its minimum contrast ratio', () => {
      const ratio = contrastRatio(pair.fg, pair.bg);
      expect(ratio, `${pair.fg} on ${pair.bg} = ${ratio.toFixed(2)}`).toBeGreaterThanOrEqual(pair.min);
    });
  });

  it('onColor reaches at least 4.5:1 or the best of black and white on sample primaries', () => {
    for (const bg of SAMPLE_PRIMARIES) {
      const chosen = onColor(bg);
      expect(['#000000', '#ffffff'], bg).toContain(chosen);
      const best = Math.max(contrastRatio('#000000', bg), contrastRatio('#ffffff', bg));
      expect(contrastRatio(chosen, bg), bg).toBeCloseTo(best, 10);
      // Black or white always reaches at least 4.58:1 on some side for any colour.
      expect(best, bg).toBeGreaterThanOrEqual(4.5);
    }
  });
});
