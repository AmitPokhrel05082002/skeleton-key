import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';
import { onColor } from '../pure/contrast';
import { ResolvedTheme, THEME_STYLESHEETS, ThemeMode, resolveTheme } from './theme-tokens';

export const THEME_LINK_ID = 'sk-theme';
const DARK_QUERY = '(prefers-color-scheme: dark)';
const HEX_COLOR = /^#[0-9a-fA-F]{6}$/;

/**
 * Owns the single `<link id="sk-theme">` that loads the prebuilt NG-ZORRO dark or light stylesheet,
 * and the `--sk-primary` / `--sk-on-primary` custom properties read by the Primary_Override.
 * NzConfigService is deliberately not used.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly doc = inject(DOCUMENT);
  private mediaQuery: MediaQueryList | null = null;
  private mediaListener: (() => void) | null = null;
  private current: ResolvedTheme | null = null;

  /** Applies a theme mode (dark when undefined). Resolves when the stylesheet has loaded or failed. */
  apply(mode?: ThemeMode | null): Promise<void> {
    this.stopListening();
    if (mode === 'auto') {
      const mq = this.doc.defaultView?.matchMedia?.(DARK_QUERY) ?? null;
      if (mq) {
        this.mediaQuery = mq;
        this.mediaListener = () => {
          void this.setResolved(resolveTheme('auto', mq.matches));
        };
        mq.addEventListener('change', this.mediaListener);
      }
      return this.setResolved(resolveTheme('auto', mq?.matches ?? true));
    }
    return this.setResolved(resolveTheme(mode, false));
  }

  /** Sets or removes the Primary_Override custom properties on `<html>`. */
  applyBranding(primaryColor?: string | null): void {
    const style = this.doc.documentElement.style;
    if (primaryColor && HEX_COLOR.test(primaryColor)) {
      style.setProperty('--sk-primary', primaryColor);
      style.setProperty('--sk-on-primary', onColor(primaryColor));
    } else {
      style.removeProperty('--sk-primary');
      style.removeProperty('--sk-on-primary');
    }
  }

  private stopListening(): void {
    if (this.mediaQuery && this.mediaListener) {
      this.mediaQuery.removeEventListener('change', this.mediaListener);
    }
    this.mediaQuery = null;
    this.mediaListener = null;
  }

  private setResolved(theme: ResolvedTheme): Promise<void> {
    const root = this.doc.documentElement;
    root.setAttribute('data-theme', theme);
    const link = this.ensureLink();
    const href = THEME_STYLESHEETS[theme];
    if (this.current === theme && link.getAttribute('href') === href) {
      return Promise.resolve();
    }
    this.current = theme;
    return new Promise<void>((resolve) => {
      const done = () => {
        link.removeEventListener('load', done);
        link.removeEventListener('error', done);
        resolve();
      };
      link.addEventListener('load', done);
      link.addEventListener('error', done);
      link.setAttribute('href', href);
    });
  }

  private ensureLink(): HTMLLinkElement {
    let link = this.doc.getElementById(THEME_LINK_ID) as HTMLLinkElement | null;
    if (!link) {
      link = this.doc.createElement('link');
      link.id = THEME_LINK_ID;
      link.rel = 'stylesheet';
      this.doc.head.appendChild(link);
    }
    return link;
  }
}
