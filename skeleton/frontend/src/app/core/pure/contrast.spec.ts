import { describe, expect, it } from 'vitest';
import { contrastRatio, onColor } from './contrast';
import { resolveTheme, THEME_TOKEN_PAIRS } from '../theme/theme-tokens';

describe('contrast', () => {
  it('computes known WCAG ratios', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 5);
    expect(contrastRatio('#777', '#777')).toBeCloseTo(1, 5);
  });

  it('is symmetric', () => {
    expect(contrastRatio('#ff7a1a', '#141414')).toBeCloseTo(contrastRatio('#141414', '#ff7a1a'), 10);
  });

  it('onColor picks the higher-contrast text colour', () => {
    for (const bg of ['#000000', '#ffffff', '#ff7a1a', '#1890ff', '#0b5394', '#fadb14', '#52c41a', '#777777']) {
      const chosen = onColor(bg);
      const other = chosen === '#000000' ? '#ffffff' : '#000000';
      expect(contrastRatio(chosen, bg)).toBeGreaterThanOrEqual(contrastRatio(other, bg));
    }
  });

  it('every theme token pair meets its minimum ratio', () => {
    for (const p of THEME_TOKEN_PAIRS) {
      expect(contrastRatio(p.fg, p.bg), p.name).toBeGreaterThanOrEqual(p.min);
    }
  });
});

describe('resolveTheme', () => {
  it('defaults to dark and honours explicit modes', () => {
    expect(resolveTheme(undefined, false)).toBe('dark');
    expect(resolveTheme('dark', false)).toBe('dark');
    expect(resolveTheme('light', true)).toBe('light');
  });

  it('auto follows the browser preference', () => {
    expect(resolveTheme('auto', true)).toBe('dark');
    expect(resolveTheme('auto', false)).toBe('light');
  });
});
