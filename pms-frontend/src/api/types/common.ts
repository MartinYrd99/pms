/** The shape every non-2xx response carries at minimum, before any endpoint-specific fields. */
export interface ErrorResponseBody {
  code: string;
  message: string;
}

/** Offset-pagination envelope shared by every paginated list endpoint. */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
}