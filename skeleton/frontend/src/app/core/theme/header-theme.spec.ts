import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { contrastRatio } from '../pure/contrast';
import { ThemeService } from './theme.service';
import { THEME_VARIABLES } from './theme-tokens';

interface NodeLike {
  getBuiltinModule(id: string): { readFileSync(path: string, encoding: string): string };
  cwd(): string;
}

/** The spec runs under Node; the bundler cannot resolve `node:fs`, so it is fetched at run time. */
function read(relative: string): string {
  const proc = (globalThis as unknown as { process: NodeLike }).process;
  return proc.getBuiltinModule('node:fs').readFileSync(`${proc.cwd()}/src/app/${relative}`, 'utf8');
}

/** Body of the first rule whose selector is exactly `selector`. */
function ruleBody(scss: string, selector: string): string {
  const start = scss.indexOf(`${selector} {`);
  expect(start, `rule ${selector}`).toBeGreaterThanOrEqual(0);
  return scss.slice(scss.indexOf('{', start) + 1, scss.indexOf('\n}', start));
}

describe('shell header colours', () => {
  const scss = read('core/theme/primary-override.scss');

  it('takes background and text from theme variables, with the brand colour first', () => {
    const body = ruleBody(scss, 'nz-header.sk-header');
    expect(body).toMatch(/background:\s*var\(--sk-primary,\s*var\(--sk-header-bg\)\)/);
    expect(body).toMatch(/color:\s*var\(--sk-on-primary,\s*var\(--sk-header-fg\)\)/);
  });

  it("outranks NG-ZORRO's .ant-layout-header (element plus class beats a single class)", () => {
    expect(scss).toContain('nz-header.sk-header {');
    expect(scss).not.toMatch(/^\.sk-header\s*\{/m);
  });

  it.each(['light', 'dark'] as const)('%s header text reaches 4.5:1 on the header background', (theme) => {
    const v = THEME_VARIABLES[theme];
    expect(contrastRatio(v['--sk-header-fg'], v['--sk-header-bg'])).toBeGreaterThanOrEqual(4.5);
  });

  it('light theme uses a light background with dark text', () => {
    const v = THEME_VARIABLES.light;
    expect(contrastRatio(v['--sk-header-bg'], '#ffffff')).toBeLessThan(1.5);
    expect(contrastRatio(v['--sk-header-fg'], '#000000')).toBeLessThan(contrastRatio(v['--sk-header-fg'], '#ffffff'));
  });

  it.each([
    ['grievance', '#7c3aed'],
    ['asset register', '#0d9488'],
    ['default blue', '#1677ff'],
    ['pale yellow', '#fadb14'],
  ])('brand colour of %s (%s) gives header text with at least 4.5:1', (_name, primary) => {
    TestBed.configureTestingModule({});
    TestBed.inject(ThemeService).applyBranding(primary);
    const style = document.documentElement.style;
    expect(style.getPropertyValue('--sk-primary')).toBe(primary);
    expect(contrastRatio(style.getPropertyValue('--sk-on-primary'), primary)).toBeGreaterThanOrEqual(4.5);
  });

  it.each(['features/shell/shell.component.ts', 'features/admin/admin-shell.component.ts'])(
    '%s uses the themed sk-header',
    (file) => {
      expect(read(file)).toContain('<nz-header class="sk-header">');
    },
  );

  beforeEach(() => {
    document.documentElement.style.removeProperty('--sk-primary');
    document.documentElement.style.removeProperty('--sk-on-primary');
  });
});
