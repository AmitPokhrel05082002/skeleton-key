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
 * Values of the `--sk-*` admin colour variables. primary-override.scss declares the same values
 * (`:root` is light, `[data-theme="dark"]` overrides); a spec asserts the two stay equal.
 */
export const THEME_VARIABLES: Readonly<Record<ResolvedTheme, Readonly<Record<string, string>>>> = {
  light: {
    '--sk-code-bg': '#ffffff',
    '--sk-code-fg': '#1f2328',
    '--sk-code-caret': '#1f2328',
    '--sk-code-selection-bg': '#b6d7ff',
    '--sk-code-selection-fg': '#000000',
    '--sk-code-border': '#8c8c8c',
    '--sk-warn-fg': '#ad4e00',
    '--sk-error-fg': '#cf1322',
    '--sk-divider': '#eeeeee',
  },
  dark: {
    '--sk-code-bg': '#0d1117',
    '--sk-code-fg': '#e6edf3',
    '--sk-code-caret': '#e6edf3',
    '--sk-code-selection-bg': '#264f78',
    '--sk-code-selection-fg': '#ffffff',
    '--sk-code-border': '#6b6b6b',
    '--sk-warn-fg': '#ffa940',
    '--sk-error-fg': '#ff7875',
    '--sk-divider': '#303030',
  },
};

function codePairs(theme: ResolvedTheme): TokenPair[] {
  const v = THEME_VARIABLES[theme];
  const surface = theme === 'dark' ? '#141414' : '#ffffff';
  return [
    { name: `${theme} code text on code background`, fg: v['--sk-code-fg'], bg: v['--sk-code-bg'], min: 4.5, theme },
    { name: `${theme} code caret on code background`, fg: v['--sk-code-caret'], bg: v['--sk-code-bg'], min: 3, theme },
    { name: `${theme} code selection text on selection background`, fg: v['--sk-code-selection-fg'], bg: v['--sk-code-selection-bg'], min: 4.5, theme },
    { name: `${theme} code border on code background`, fg: v['--sk-code-border'], bg: v['--sk-code-bg'], min: 3, theme },
    { name: `${theme} warning text on surface`, fg: v['--sk-warn-fg'], bg: surface, min: 4.5, theme },
    { name: `${theme} error text on surface`, fg: v['--sk-error-fg'], bg: surface, min: 4.5, theme },
  ];
}

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
  // Admin code editor and status colours
  ...codePairs('dark'),
  ...codePairs('light'),
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
