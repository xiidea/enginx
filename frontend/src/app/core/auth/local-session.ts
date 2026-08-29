import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../../environments/environment';

/**
 * A session backed by a platform-issued token rather than an identity provider.
 *
 * The token is held in `localStorage` so a refresh does not sign the user out. That is a deliberate
 * trade and worth naming: `localStorage` is readable by any script running on this origin, so a
 * cross-site scripting bug becomes a stolen session. The alternative — a cookie the API sets —
 * needs CSRF protection on every mutating request, and the API is deliberately cookie-free so it
 * has no CSRF surface at all. The console's own content security policy is what keeps the first
 * risk small: `default-src 'none'`, no inline script, no third-party origins.
 */
@Injectable({ providedIn: 'root' })
export class LocalSession {
  private static readonly STORAGE_KEY = 'enginx.local.token';

  private readonly http = inject(HttpClient);

  readonly token = signal<string | null>(null);

  /** True when the account must set a new password before it can do anything else. */
  readonly mustChangePassword = signal(false);

  /**
   * Restores a stored token, discarding one that has already expired.
   *
   * Expiry is checked here rather than waiting for the first 401 so the console does not render
   * a signed-in shell and then empty every page.
   */
  restore(): void {
    let stored: string | null = null;
    try {
      stored = localStorage.getItem(LocalSession.STORAGE_KEY);
    } catch {
      // Private browsing, or storage disabled. No stored session is a valid state.
      return;
    }
    if (!stored || this.isExpired(stored)) {
      this.clear();
      return;
    }
    this.adopt(stored);
  }

  async login(username: string, password: string): Promise<void> {
    const response = await firstValueFrom(
      this.http.post<{ accessToken: string }>(`${environment.apiBase}/auth/login`, {
        username,
        password,
      }),
    );
    this.adopt(response.accessToken);
  }

  clear(): void {
    this.token.set(null);
    this.mustChangePassword.set(false);
    try {
      localStorage.removeItem(LocalSession.STORAGE_KEY);
    } catch {
      // Nothing to remove if storage is unavailable.
    }
  }

  private adopt(token: string): void {
    this.token.set(token);
    this.mustChangePassword.set(claimsOf(token)?.must_change_password === true);
    try {
      localStorage.setItem(LocalSession.STORAGE_KEY, token);
    } catch {
      // The session still works for this tab; it simply will not survive a refresh.
    }
  }

  private isExpired(token: string): boolean {
    const exp = claimsOf(token)?.exp;
    // A token with no expiry is not one this platform issued, so it is treated as unusable.
    return typeof exp !== 'number' || exp * 1000 <= Date.now();
  }
}

export interface LocalTokenClaims {
  exp?: number;
  sub?: string;
  preferred_username?: string;
  name?: string;
  groups?: string[];
  realm_access?: { roles?: string[] };
  must_change_password?: boolean;
}

/**
 * Reads a token's claims without verifying it.
 *
 * Display only, and safe for that: the server validates the signature on every request, so a
 * forged token changes what this console renders and nothing it can actually do.
 */
export function claimsOf(token: string): LocalTokenClaims | null {
  try {
    const payload = token.split('.')[1];
    return JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
  } catch {
    return null;
  }
}
