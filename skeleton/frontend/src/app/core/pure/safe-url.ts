// eslint-disable-next-line no-control-regex
const CONTROL_CHARS = /[\u0000-\u001f\u007f]/;
const SCHEME = /^([a-zA-Z][a-zA-Z0-9+.-]*):/;

/**
 * Returns the trimmed URL when its scheme is http or https, or when it is a same-origin
 * relative path. Returns null otherwise (javascript:, data:, protocol-relative, etc.).
 */
export function safeLogoUrl(url: string | null | undefined): string | null {
  if (typeof url !== 'string') {
    return null;
  }
  const value = url.trim();
  if (value === '' || CONTROL_CHARS.test(value)) {
    return null;
  }
  const scheme = SCHEME.exec(value);
  if (scheme) {
    const name = scheme[1].toLowerCase();
    return name === 'http' || name === 'https' ? value : null;
  }
  // Protocol-relative ("//host") and backslash forms point to another origin.
  if (value.startsWith('//') || value.startsWith('\\') || value.includes('\\')) {
    return null;
  }
  return value;
}
