import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { toApiError } from './api-error';
import { displayName } from '../pure/display-name';

describe('toApiError', () => {
  it('maps no response to NETWORK', () => {
    const e = toApiError(new HttpErrorResponse({ status: 0 }));
    expect(e.status).toBe(0);
    expect(e.code).toBe('NETWORK');
    expect(e.violations).toEqual([]);
  });

  it('reads an ApiError body', () => {
    const e = toApiError(
      new HttpErrorResponse({
        status: 422,
        error: {
          status: 422,
          code: 'VALIDATION',
          message: 'bad',
          requestId: 'r1',
          violations: [{ field: 'a', rule: 'required' }],
          line: 2,
          column: 5,
        },
      }),
    );
    expect(e).toEqual({
      status: 422,
      code: 'VALIDATION',
      message: 'bad',
      requestId: 'r1',
      violations: [{ field: 'a', rule: 'required' }],
      line: 2,
      column: 5,
    });
  });

  it('tolerates a non-JSON body', () => {
    const e = toApiError(new HttpErrorResponse({ status: 502, error: '<html>Bad Gateway</html>' }));
    expect(e.status).toBe(502);
    expect(e.code).toBe('HTTP_502');
    expect(e.violations).toEqual([]);
  });

  it('is idempotent for an already mapped ApiError', () => {
    const mapped = toApiError(
      new HttpErrorResponse({
        status: 422,
        error: { code: 'VALIDATION', message: 'bad', requestId: 'r1', violations: [{ field: 'a', rule: 'required' }], line: 2 },
      }),
    );
    const again = toApiError(mapped);
    expect(again).toEqual(mapped);
    expect(again.code).toBe('VALIDATION');
    expect(again.violations).toEqual([{ field: 'a', rule: 'required' }]);
    expect(toApiError(again)).toEqual(mapped);
  });

  it('does not treat a partial object as an ApiError', () => {
    expect(toApiError({ status: 500 }).code).toBe('HTTP_500');
  });
});

describe('displayName', () => {
  it('prefers name, then id, then dash', () => {
    expect(displayName('Ann', 'u1')).toBe('Ann');
    expect(displayName('  ', 'u1')).toBe('u1');
    expect(displayName(null, undefined)).toBe('-');
  });
});
