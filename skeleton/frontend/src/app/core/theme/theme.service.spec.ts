import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ThemeService } from './theme.service';
import { THEME_STYLESHEETS } from './theme-tokens';

describe('ThemeService', () => {
  let service: ThemeService;

  beforeEach(() => {
    document.getElementById('sk-theme')?.remove();
    document.documentElement.removeAttribute('data-theme');
    service = TestBed.inject(ThemeService);
  });

  afterEach(() => {
    document.getElementById('sk-theme')?.remove();
    document.documentElement.removeAttribute('style');
  });

  function link(): HTMLLinkElement {
    return document.getElementById('sk-theme') as HTMLLinkElement;
  }

  it('defaults to dark and keeps a single link', () => {
    void service.apply();
    expect(link().getAttribute('href')).toBe(THEME_STYLESHEETS.dark);
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    void service.apply('light');
    expect(document.querySelectorAll('#sk-theme').length).toBe(1);
    expect(link().getAttribute('href')).toBe(THEME_STYLESHEETS.light);
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('sets and removes the branding variables', () => {
    service.applyBranding('#ffffff');
    const style = document.documentElement.style;
    expect(style.getPropertyValue('--sk-primary')).toBe('#ffffff');
    expect(style.getPropertyValue('--sk-on-primary')).toBe('#000000');
    service.applyBranding();
    expect(style.getPropertyValue('--sk-primary')).toBe('');
    expect(style.getPropertyValue('--sk-on-primary')).toBe('');
  });
});
