/** True for undefined, null, '', [] and a location whose lat and lng are both unset. */
export function isEmptyValue(v: unknown): boolean {
  if (v === undefined || v === null || v === '') {
    return true;
  }
  if (Array.isArray(v)) {
    return v.length === 0;
  }
  if (typeof v === 'object' && !(v instanceof Date)) {
    const o = v as Record<string, unknown>;
    const keys = Object.keys(o);
    if (keys.length === 2 && 'lat' in o && 'lng' in o) {
      return (o['lat'] === null || o['lat'] === undefined) && (o['lng'] === null || o['lng'] === undefined);
    }
  }
  return false;
}
