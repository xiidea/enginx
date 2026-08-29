import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Problem } from './models';

/**
 * The single place HTTP concerns live.
 *
 * Every failure is normalised into an RFC 9457 problem document so callers never have to guess at
 * the shape of an error, and a network failure — where there is no response at all — is given the
 * same shape rather than surfacing as an unrelated exception type.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  readonly base = environment.apiBase;

  get<T>(path: string, params?: Record<string, string | number | boolean | undefined | null | string[]>): Observable<T> {
    return this.http.get<T>(`${this.base}${path}`, { params: toParams(params) }).pipe(catchError(asProblem));
  }

  post<T>(path: string, body?: unknown): Observable<T> {
    return this.http.post<T>(`${this.base}${path}`, body ?? {}).pipe(catchError(asProblem));
  }

  put<T>(path: string, body: unknown, ifMatch?: number): Observable<T> {
    return this.http
      .put<T>(`${this.base}${path}`, body, { headers: ifMatchHeader(ifMatch) })
      .pipe(catchError(asProblem));
  }

  delete<T>(path: string, ifMatch?: number): Observable<T> {
    return this.http
      .delete<T>(`${this.base}${path}`, { headers: ifMatchHeader(ifMatch) })
      .pipe(catchError(asProblem));
  }
}

/**
 * The version last read, sent back so a concurrent edit is refused rather than silently
 * overwriting someone else's change.
 */
function ifMatchHeader(version?: number): Record<string, string> {
  return version === undefined ? {} : { 'If-Match': `"${version}"` };
}

function toParams(values?: Record<string, string | number | boolean | undefined | null | string[]>): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(values ?? {})) {
    if (value === undefined || value === null || value === '') {
      continue;
    }
    if (Array.isArray(value)) {
      // Repeated rather than comma-joined: the API splits a bound list on commas, which would
      // turn one `sort=domain,asc` into two unusable values.
      for (const entry of value) {
        params = params.append(key, entry);
      }
    } else {
      params = params.set(key, String(value));
    }
  }
  return params;
}

function asProblem(error: HttpErrorResponse) {
  if (error.error && typeof error.error === 'object' && 'title' in error.error) {
    return throwError(() => error.error as Problem);
  }

  // No response body: the server was unreachable, or something between here and it returned
  // something that is not a problem document.
  const problem: Problem = {
    type: 'about:blank',
    title: error.status === 0 ? 'Cannot reach the management API' : 'Unexpected error',
    status: error.status,
    detail:
      error.status === 0
        ? 'The management API did not respond. It may be restarting, or blocked by the browser.'
        : (error.message ?? 'The request failed.'),
  };
  return throwError(() => problem);
}
