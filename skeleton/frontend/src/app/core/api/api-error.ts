import { HttpErrorResponse } from '@angular/common/http';
import { ApiError, Violation } from './api.types';

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function str(v: unknown): string | undefined {
  return typeof v === 'string' && v !== '' ? v : undefined;
}

function num(v: unknown): number | undefined {
  return typeof v === 'number' && Number.isFinite(v) ? v : undefined;
}

function toViolations(v: unknown): Violation[] {
  if (!Array.isArray(v)) {
    return [];
  }
  const out: Violation[] = [];
  for (const item of v) {
    if (!isObject(item)) {
      continue;
    }
    const violation: Violation = {
      field: typeof item['field'] === 'string' ? item['field'] : '',
      rule: typeof item['rule'] === 'string' ? item['rule'] : '',
    };
    const message = str(item['message']);
    if (message !== undefined) {
      violation.message = message;
    }
    out.push(violation);
  }
  return out;
}

/** Parses a body that may be an object, a JSON string or anything else (for example an nginx HTML page). */
function parseBody(body: unknown): Record<string, unknown> | null {
  if (isObject(body)) {
    return body;
  }
  if (typeof body === 'string') {
    try {
      const parsed: unknown = JSON.parse(body);
      return isObject(parsed) ? parsed : null;
    } catch {
      return null;
    }
  }
  return null;
}

/**
 * Maps any thrown value to an ApiError. No response (status 0) gives code NETWORK.
 * Non-JSON or non-object bodies give a generic error carrying only the HTTP status.
 */
export function toApiError(err: unknown): ApiError {
  const status =
    err instanceof HttpErrorResponse
      ? err.status
      : isObject(err) && typeof err['status'] === 'number'
        ? err['status']
        : 0;
  const body = isObject(err) || err instanceof HttpErrorResponse ? parseBody((err as { error?: unknown }).error) : null;

  if (status === 0) {
    return {
      status: 0,
      code: 'NETWORK',
      message: 'The server could not be reached.',
      violations: [],
    };
  }

  const result: ApiError = {
    status,
    code: str(body?.['code']) ?? 'HTTP_' + status,
    message: str(body?.['message']) ?? 'Request failed with status ' + status + '.',
    violations: toViolations(body?.['violations']),
  };
  const requestId = str(body?.['requestId']);
  if (requestId !== undefined) {
    result.requestId = requestId;
  }
  const line = num(body?.['line']);
  if (line !== undefined) {
    result.line = line;
  }
  const column = num(body?.['column']);
  if (column !== undefined) {
    result.column = column;
  }
  return result;
}
