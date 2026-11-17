import { describe, expect, it } from 'vitest';
import { THEME_VARIABLES } from './theme-tokens';

interface NodeLike {
  getBuiltinModule(id: string): { readFileSync(path: string, encoding: string): string };
  cwd(): string;
}

/** The spec runs under Node; the bundler cannot resolve `node:fs`, so it is fetched at run time. */
function readScss(): string {
  const proc = (globalThis as unknown as { process: NodeLike }).process;
  const fs = proc.getBuiltinModule('node:fs');
  return fs.readFileSync(`${proc.cwd()}/src/app/core/theme/primary-override.scss`, 'utf8');
}

/** Declarations of the first rule whose selector is exactly `selector`. */
function declarations(scss: string, selector: string): Record<string, string> {
  const start = scss.indexOf(`${selector} {`);
  expect(start, `rule ${selector}`).toBeGreaterThanOrEqual(0);
  const body = scss.slice(scss.indexOf('{', start) + 1, scss.indexOf('\n}', start));
  const out: Record<string, string> = {};
  for (const m of body.matchAll(/(--sk-[a-z-]+):\s*([^;]+);/g)) out[m[1]] = m[2].trim();
  return out;
}

describe('admin theme variables in primary-override.scss', () => {
  const scss = readScss();

  it('light values in :root equal THEME_VARIABLES.light', () => {
    const css = declarations(scss, ':root');
    for (const [name, value] of Object.entries(THEME_VARIABLES.light)) expect(css[name], name).toBe(value);
  });

  it("dark values in [data-theme='dark'] equal THEME_VARIABLES.dark exactly", () => {
    expect(declarations(scss, "[data-theme='dark']")).toEqual(THEME_VARIABLES.dark);
  });
});
