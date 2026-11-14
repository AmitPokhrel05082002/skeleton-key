import { onColor } from '../pure/contrast';

export type ThemeMode = 'dark' | 'light' | 'auto';
export type ResolvedTheme = 'dark' | 'light';

/** Prebuilt NG-ZORRO stylesheets, copied as assets by angular.json. */
export const THEME_STYLESHEETS: Readonly<Record<ResolvedTheme, string>> = {
  dark: 'assets/themes/ng-zorro-antd.dark.css',
  light: 'assets/themes/ng-zorro-antd.css',
};

export interface TokenPair {
  name: string;
  fg: string;
  bg: string;
  min: 4.5 | 3;
  theme: 'dark' | 'light';
}

/** Skeleton Key accent. Used for highlights, never as text on a surface that fails contrast. */
export const ACCENT = '#ff7a1a';

/**
 * Foreground/background pairs (opaque equivalents of the NG-ZORRO prebuilt themes) with the
 * minimum WCAG ratio each must reach. 4.5 for text, 3 for large text and component borders.
 */
export const THEME_TOKEN_PAIRS: readonly TokenPair[] = [
  // Dark theme (container background #141414)
  { name: 'dark text on surface', fg: '#dcdcdc', bg: '#141414', min: 4.5, theme: 'dark' },
  { name: 'dark secondary text on surface', fg: '#adadad', bg: '#141414', min: 4.5, theme: 'dark' },
  { name: 'dark text on layout', fg: '#dcdcdc', bg: '#000000', min: 4.5, theme: 'dark' },
  { name: 'dark border on surface', fg: '#6b6b6b', bg: '#141414', min: 3, theme: 'dark' },
  { name: 'dark accent on surface', fg: ACCENT, bg: '#141414', min: 4.5, theme: 'dark' },
  { name: 'dark text on accent', fg: onColor(ACCENT), bg: ACCENT, min: 4.5, theme: 'dark' },
  // Light theme
  { name: 'light text on surface', fg: '#262626', bg: '#ffffff', min: 4.5, theme: 'light' },
  { name: 'light secondary text on surface', fg: '#595959', bg: '#ffffff', min: 4.5, theme: 'light' },
  { name: 'light text on layout', fg: '#262626', bg: '#f5f5f5', min: 4.5, theme: 'light' },
  { name: 'light border on surface', fg: '#8c8c8c', bg: '#ffffff', min: 3, theme: 'light' },
  { name: 'light text on accent', fg: onColor(ACCENT), bg: ACCENT, min: 4.5, theme: 'light' },
];

/** Decides the theme to apply. Dark is the default when no mode is given. */
export function resolveTheme(mode: ThemeMode | undefined | null, prefersDark: boolean): ResolvedTheme {
  switch (mode) {
    case 'light':
      return 'light';
    case 'auto':
      return prefersDark ? 'dark' : 'light';
    default:
      return 'dark';
  }
}
