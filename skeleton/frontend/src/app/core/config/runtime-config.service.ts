import { Injectable, computed, signal } from '@angular/core';
import { RuntimeConfig, validateRuntimeConfig } from './host-resolver';

const DEFAULT_API_BASE = '/api';

@Injectable({ providedIn: 'root' })
export class RuntimeConfigService {
  private readonly _config = signal<RuntimeConfig | null>(null);
  private readonly _error = signal<string | null>(null);

  readonly config = this._config.asReadonly();
  readonly configError = this._error.asReadonly();
  readonly apiBase = computed(() => {
    const base = this._config()?.apiBasePath?.trim();
    return base ? base.replace(/\/+$/, '') || DEFAULT_API_BASE : DEFAULT_API_BASE;
  });

  /** Loads assets/config.json. Never throws; failure is reported through configError. */
  async load(): Promise<boolean> {
    try {
      const response = await fetch('assets/config.json', { cache: 'no-store' });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const body: unknown = await response.json();
      if (!validateRuntimeConfig(body)) {
        throw new Error('Invalid runtime configuration');
      }
      this._config.set(body);
      this._error.set(null);
      return true;
    } catch (e) {
      this._config.set(null);
      this._error.set(e instanceof Error ? e.message : 'Configuration error');
      return false;
    }
  }
}
