/** Name when it has non-blank text, otherwise the id, otherwise '-'. */
export function displayName(name: string | null | undefined, id: string | null | undefined): string {
  if (typeof name === 'string' && name.trim() !== '') {
    return name;
  }
  if (typeof id === 'string' && id.trim() !== '') {
    return id;
  }
  return '-';
}
