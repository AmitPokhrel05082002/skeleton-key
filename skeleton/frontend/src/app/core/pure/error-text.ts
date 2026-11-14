import type { ApiError } from '../api/api.types';

export const GENERIC_SERVER_ERROR = 'Something went wrong on the server. Please try again later.';

/**
 * User-facing text for an error. For 5xx the server message is never shown: generic text
 * plus the requestId when present.
 */
export function errorText(err: ApiError): string {
  if (err.status >= 500) {
    return err.requestId ? `${GENERIC_SERVER_ERROR} (Request ID: ${err.requestId})` : GENERIC_SERVER_ERROR;
  }
  return err.message;
}
