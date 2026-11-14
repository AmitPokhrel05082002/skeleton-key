export interface RuntimeConfig {
  apiBasePath?: string;
  hosts: Record<string, string>;
}

/**
 * Returns the app code mapped to the host, or null. Strips a trailing `:port`,
 * lower-cases the host and does an own-property exact match on lower-cased keys.
 */
export function resolveDefaultApp(hosts: Record<string, string>, host: string): string | null {
  const wanted = host.replace(/:\d*$/, '').toLowerCase();
  if (!wanted) {
    return null;
  }
  for (const key of Object.keys(hosts)) {
    if (key.toLowerCase() === wanted && Object.prototype.hasOwnProperty.call(hosts, key)) {
      const code = hosts[key];
      return typeof code === 'string' ? code : null;
    }
  }
  return null;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Accepts a value only if it is an object whose `hosts` is an object with string values. */
export function validateRuntimeConfig(value: unknown): value is RuntimeConfig {
  if (!isPlainObject(value)) {
    return false;
  }
  const hosts = value['hosts'];
  if (!isPlainObject(hosts)) {
    return false;
  }
  if (!Object.values(hosts).every((v) => typeof v === 'string')) {
    return false;
  }
  const base = value['apiBasePath'];
  return base === undefined || typeof base === 'string';
}
