import type { VersionRef } from '../../core/api/api.types';

/**
 * Pure helpers of the definition JSON editor (Requirements 12.10, 13.6, 14.x).
 *
 * Violation `field` formats sent by the backend (read-only reference):
 *  - schema violations: RFC 6901 JSON Pointer, "" is the root, e.g. "/app/code", "/roles/0/code";
 *  - publish-time validator violations: code-keyed dotted paths, e.g. "entities[TICKET].fields[title]",
 *    "workflows[W].steps[S].actions[A]", "workflows[W].transitions[0]" (index) or "workflows[W].transitions[S]"
 *    (step code of the `from` side), "roles[R]", "forms[F]", "views[V]";
 *  - "" for a parse failure.
 * `pointerToOffset` understands both.
 */

/** Pretty prints a JSON value with two-space indentation. */
export function formatJson(value: unknown): string {
  return JSON.stringify(value, null, 2) ?? 'null';
}

export interface JsonState {
  valid: boolean;
  /** Parsed value when valid. */
  value?: unknown;
  /** `JSON.parse` message when invalid. */
  error?: string;
}

/** Reports whether the text is parseable JSON (valid iff `JSON.parse` succeeds). */
export function jsonState(text: string): JsonState {
  try {
    return { valid: true, value: JSON.parse(text) };
  } catch (e) {
    return { valid: false, error: e instanceof Error ? e.message : String(e) };
  }
}

// ---------------------------------------------------------------------------------------------
// Locating values in text
// ---------------------------------------------------------------------------------------------

interface Node {
  start: number;
  entries?: Map<string, Node>;
  /** Object entries in order, with the raw key (for lookup by `code`/`from`). */
  items?: Node[];
}

const MAX_DEPTH = 256;

class Scanner {
  pos = 0;
  constructor(readonly text: string) {}

  ws(): void {
    while (this.pos < this.text.length && ' \t\r\n'.includes(this.text[this.pos])) this.pos++;
  }

  fail(): never {
    throw new Error('malformed');
  }

  value(depth: number): Node {
    if (depth > MAX_DEPTH) this.fail();
    this.ws();
    const start = this.pos;
    const c = this.text[this.pos];
    if (c === '{') return this.object(start, depth);
    if (c === '[') return this.array(start, depth);
    if (c === '"') {
      this.string();
      return { start };
    }
    const m = /^(?:-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null)/.exec(this.text.slice(this.pos, this.pos + 64));
    if (!m) this.fail();
    this.pos += m[0].length;
    return { start };
  }

  string(): string {
    const begin = this.pos;
    this.pos++;
    while (this.pos < this.text.length) {
      const c = this.text[this.pos];
      if (c === '\\') this.pos += 2;
      else if (c === '"') {
        this.pos++;
        return JSON.parse(this.text.slice(begin, this.pos)) as string;
      } else this.pos++;
    }
    return this.fail();
  }

  object(start: number, depth: number): Node {
    const entries = new Map<string, Node>();
    const items: Node[] = [];
    this.pos++;
    this.ws();
    if (this.text[this.pos] === '}') {
      this.pos++;
      return { start, entries, items };
    }
    for (;;) {
      this.ws();
      if (this.text[this.pos] !== '"') this.fail();
      const key = this.string();
      this.ws();
      if (this.text[this.pos++] !== ':') this.fail();
      const node = this.value(depth + 1);
      entries.set(key, node);
      items.push(node);
      this.ws();
      const c = this.text[this.pos++];
      if (c === '}') return { start, entries, items };
      if (c !== ',') this.fail();
    }
  }

  array(start: number, depth: number): Node {
    const items: Node[] = [];
    this.pos++;
    this.ws();
    if (this.text[this.pos] === ']') {
      this.pos++;
      return { start, items };
    }
    for (;;) {
      items.push(this.value(depth + 1));
      this.ws();
      const c = this.text[this.pos++];
      if (c === ']') return { start, items };
      if (c !== ',') this.fail();
    }
  }
}

function parseTree(text: string): Node | null {
  try {
    const s = new Scanner(text);
    const root = s.value(0);
    s.ws();
    return s.pos === text.length ? root : null;
  } catch {
    return null;
  }
}

function unescapePointer(seg: string): string {
  return seg.replace(/~1/g, '/').replace(/~0/g, '~');
}

/** One step of a path: an object key, or a bracket selector applied to the array found under that key. */
interface Segment {
  key: string;
  selector?: string;
}

function parsePath(path: string): Segment[] | null {
  if (path === '') return [];
  if (path.startsWith('/')) {
    return path
      .slice(1)
      .split('/')
      .map((s) => ({ key: unescapePointer(s) }));
  }
  const segments: Segment[] = [];
  for (const part of path.split('.')) {
    const m = /^([A-Za-z_$][\w$]*)(?:\[([^\]]*)\])?$/.exec(part);
    if (!m) return null;
    segments.push({ key: m[1], selector: m[2] });
  }
  return segments;
}

function child(node: Node, key: string): Node | null {
  if (node.entries) return node.entries.get(key) ?? null;
  if (node.items && /^(0|[1-9]\d*)$/.test(key)) return node.items[Number(key)] ?? null;
  return null;
}

/** Looks an array element up by numeric index or by its `code` (or `from`, for transitions) property. */
function select(array: Node, selector: string, text: string): Node | null {
  if (!array.items || array.entries) return null;
  if (/^(0|[1-9]\d*)$/.test(selector)) {
    const byIndex = array.items[Number(selector)];
    // An index wins unless it is also a code of an element (codes are upper snake or lowerCamel, never digits only).
    if (byIndex) return byIndex;
  }
  for (const item of array.items) {
    if (!item.entries) continue;
    for (const prop of ['code', 'from']) {
      const n = item.entries.get(prop);
      if (n && text[n.start] === '"' && stringAt(text, n.start) === selector) return item;
    }
  }
  return null;
}

function stringAt(text: string, start: number): string | null {
  try {
    const s = new Scanner(text);
    s.pos = start;
    return s.string();
  } catch {
    return null;
  }
}

/**
 * Offset in `text` where the value addressed by `pointer` starts. Accepts a JSON Pointer ("/a/0/b", "" = root)
 * or the backend validator's dotted code path ("workflows[W].steps[S].actions[A]").
 * Never throws. Returns `null` when the text is not valid JSON, the path is malformed, or the path does not exist.
 */
export function pointerToOffset(text: string, pointer: string): number | null {
  try {
    if (typeof text !== 'string' || typeof pointer !== 'string') return null;
    const segments = parsePath(pointer);
    const root = parseTree(text);
    if (!segments || !root) return null;
    let node: Node = root;
    for (const seg of segments) {
      const next = child(node, seg.key);
      if (!next) return null;
      node = next;
      if (seg.selector !== undefined) {
        const picked = select(node, seg.selector, text);
        if (!picked) return null;
        node = picked;
      }
    }
    return node.start;
  } catch {
    return null;
  }
}

/**
 * Converts a 1-based line and column to a 0-based offset. Line and column are clamped into the text:
 * a line past the end gives the end of the text, a column past the end of the line gives the line end.
 * Non-finite or non-positive inputs count as 1.
 */
export function lineColumnToOffset(text: string, line: number, column: number): number {
  const l = Number.isFinite(line) ? Math.max(1, Math.floor(line)) : 1;
  const c = Number.isFinite(column) ? Math.max(1, Math.floor(column)) : 1;
  let lineStart = 0;
  for (let current = 1; current < l; current++) {
    const nl = text.indexOf('\n', lineStart);
    if (nl < 0) return text.length;
    lineStart = nl + 1;
  }
  let lineEnd = text.indexOf('\n', lineStart);
  if (lineEnd < 0) lineEnd = text.length;
  else if (lineEnd > lineStart && text[lineEnd - 1] === '\r') lineEnd--;
  return Math.min(lineStart + c - 1, lineEnd);
}

// ---------------------------------------------------------------------------------------------
// Save / publish
// ---------------------------------------------------------------------------------------------

/** What the editor knows about the document it holds. */
export interface EditorState {
  /** The stored version being edited, or null for a definition that was never saved. */
  saved: VersionRef | null;
  /** True when the text differs from the last saved text. */
  dirty: boolean;
}

/** Endpoint to call on Save: POST createDefinition, PUT updateDefinition, EDIT editPublished. */
export type SaveChoice = 'POST' | 'PUT' | 'EDIT';

export function chooseSave(state: EditorState): SaveChoice {
  if (!state.saved) return 'POST';
  return state.saved.status === 'DRAFT' ? 'PUT' : 'EDIT';
}

/** Publish is allowed only for a saved DRAFT with no unsaved changes. */
export function canPublish(state: EditorState): boolean {
  return state.saved !== null && state.saved.status === 'DRAFT' && !state.dirty;
}

// ---------------------------------------------------------------------------------------------
// Template
// ---------------------------------------------------------------------------------------------

/**
 * A minimal definition valid against schema/app-definition.schema.json and the publish-time validator:
 * one role, one entity with one field, one workflow START(TASK) -> DONE(END), every status settable.
 * Returns a fresh object on every call.
 */
export function minimalDefinitionTemplate(): Record<string, unknown> {
  return {
    schemaVersion: 1,
    app: { code: 'NEW_APP', name: 'New app' },
    roles: [{ code: 'USER', label: 'User' }],
    entities: [
      {
        code: 'ITEM',
        label: 'Item',
        titleField: 'title',
        fields: [{ code: 'title', label: 'Title', type: 'TEXT', required: true }],
      },
    ],
    workflows: [
      {
        code: 'MAIN',
        label: 'Main',
        entity: 'ITEM',
        startStep: 'START',
        initialStatus: 'Open',
        statuses: [
          { label: 'Open', category: 'OPEN' },
          { label: 'Done', category: 'DONE' },
        ],
        steps: [
          {
            code: 'START',
            label: 'Start',
            type: 'TASK',
            assignee: { strategy: 'ROLE_QUEUE', role: 'USER' },
            actions: [{ code: 'FINISH', label: 'Finish', roles: ['USER'], to: 'DONE', style: 'primary' }],
          },
          { code: 'DONE', label: 'Done', type: 'END', outcome: 'Done' },
        ],
      },
    ],
  };
}
