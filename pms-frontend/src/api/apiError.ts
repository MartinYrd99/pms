/**
 * One thrown shape for every non-2xx response: the HTTP status, the backend's {code, message}
 * and the parsed response body itself, so a screen can read a 409's blocking session without
 * parsing anything.
 */
export class ApiError<TBody = unknown> extends Error {
  readonly status: number;
  readonly code: string;
  readonly body: TBody;

  constructor(status: number, code: string, message: string, body: TBody) {
    super(message);

    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

/**
 * Narrows an unknown catch value to an ApiError. The generic lets a caller assert the shape it
 * expects for a status it already knows how to handle, e.g. isApiError<SessionConflictResponse>.
 */
export function isApiError<TBody = unknown>(error: unknown): error is ApiError<TBody> {
  return error instanceof ApiError;
}