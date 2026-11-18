import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';
import type { TrackResponse } from '../../core/api/api.types';
import { EMAIL_MAX, REFERENCE_MAX, statusChipColor, toTimelineItems, validateTrackForm } from './track-model';

const alnum = fc.stringMatching(/^[a-z0-9]{1,12}$/);
const validEmail = fc
  .tuple(alnum, alnum, fc.stringMatching(/^[a-z]{2,5}$/))
  .map(([l, d, t]) => `${l}@${d}.${t}`);
const pad = fc.constantFrom('', ' ', '  ', '\t');
const validRef = fc.string({ minLength: 1, maxLength: 40 }).filter((s) => s.trim().length > 0);

// Feature: user-admin-and-tracking, Property 13
describe('validateTrackForm (Property 13)', () => {
  it('accepts a non-empty reference and a well-formed email, whatever the surrounding spaces', () => {
    fc.assert(
      fc.property(validRef, validEmail, pad, pad, (ref, email, p1, p2) => {
        expect(validateTrackForm(p1 + ref + p2, p2 + email + p1).valid).toBe(true);
      }),
    );
  });

  it('rejects a blank or over-long reference', () => {
    fc.assert(
      fc.property(validEmail, fc.constantFrom('', ' ', '   ', '\t'), (email, blank) => {
        expect(validateTrackForm(blank, email).valid).toBe(false);
      }),
    );
    expect(validateTrackForm('x'.repeat(REFERENCE_MAX + 1), 'a@b.co').valid).toBe(false);
    expect(validateTrackForm('x'.repeat(REFERENCE_MAX), 'a@b.co').valid).toBe(true);
  });

  it('rejects emails without @, with inner spaces, with an empty part, or over 254 characters', () => {
    fc.assert(
      fc.property(validRef, alnum, (ref, text) => {
        expect(validateTrackForm(ref, text).valid).toBe(false); // no @
        expect(validateTrackForm(ref, `${text} x@b.co`).valid).toBe(false); // space inside
        expect(validateTrackForm(ref, `@${text}.co`).valid).toBe(false); // no local part
        expect(validateTrackForm(ref, `${text}@`).valid).toBe(false); // no domain
        expect(validateTrackForm(ref, `${text}@${text}`).valid).toBe(false); // no dot
      }),
    );
    const long = 'a'.repeat(EMAIL_MAX - 5) + '@b.com';
    expect(validateTrackForm('R', long).valid).toBe(false);
  });
});

const hex = fc.stringMatching(/^[0-9a-fA-F]{6}$/).map((s) => '#' + s);
const status = fc.record({
  color: fc.oneof(hex, fc.constant(null), fc.string()),
  category: fc.oneof(fc.constantFrom('OPEN', 'IN_PROGRESS', 'DONE', 'REJECTED'), fc.constant(null), fc.string()),
});
const response = fc.record({
  timeline: fc.array(fc.record({ at: fc.string(), status: fc.string() })),
});

// Feature: user-admin-and-tracking, Property 14 (rendering part)
describe('track rendering helpers (Property 14)', () => {
  it('statusChipColor returns the #rrggbb colour as given, else a category colour, and never throws', () => {
    fc.assert(
      fc.property(status, (s) => {
        const c = statusChipColor(s);
        if (s.color !== null && /^#[0-9a-fA-F]{6}$/.test(s.color)) {
          expect(c).toBe(s.color);
        } else {
          expect(['blue', 'gold', 'green', 'red', 'default']).toContain(c);
        }
      }),
    );
  });

  it('toTimelineItems keeps order and count and exposes only at and status', () => {
    fc.assert(
      fc.property(response, fc.string(), (r, extra) => {
        const noisy = {
          timeline: r.timeline.map((e) => ({ ...e, secret: extra })),
        } as unknown as Pick<TrackResponse, 'timeline'>;
        const items = toTimelineItems(noisy);
        expect(items).toEqual(r.timeline);
        for (const i of items) {
          expect(Object.keys(i).sort()).toEqual(['at', 'status']);
        }
      }),
    );
  });
});
