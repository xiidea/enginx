import { Injectable } from '@angular/core';
import { AbstractSecurityStorage } from 'angular-auth-oidc-client';

/**
 * Keeps the OIDC library's state in memory, so no token ever reaches browser storage.
 *
 * Browser storage is readable by any script on this origin; a token held there turns a
 * cross-site scripting bug into a stolen session that outlives the tab. In memory, it dies with
 * the page.
 *
 * <p>The one exception is the state the authorization-code redirect must carry across the
 * navigation to the identity provider and back: the PKCE verifier, the state and the nonce, which
 * are single-use and grant nothing on their own, and the provider's discovery document, which is
 * public and which the library reads its token endpoint from when the code comes back. The flow
 * cannot complete without them. Those keys alone are mirrored to `sessionStorage`, and filtered on
 * the way back in too, so an entry left behind by an earlier build that stored tokens is never
 * read.
 *
 * <p>A reload or a new tab therefore starts without tokens. AuthService re-establishes the session
 * with a silent redirect the identity provider answers from its own session, without asking for a
 * password.
 */
@Injectable()
export class MemorySecurityStorage implements AbstractSecurityStorage {
  /** The library's keys that the code-flow redirect needs, and nothing that is a credential. */
  private static readonly REDIRECT_KEYS = [
    'authWellKnownEndPoints',
    'codeVerifier',
    'authStateControl',
    'authNonce',
    'storageCodeFlowInProgress',
    'storageCustomParamsAuthRequest',
  ];

  private readonly memory = new Map<string, string>();

  read(key: string): string | null {
    return this.memory.get(key) ?? this.persisted(key);
  }

  write(key: string, value: string): void {
    this.memory.set(key, value);
    const redirectState = MemorySecurityStorage.redirectStateOf(value);
    try {
      if (redirectState) {
        sessionStorage.setItem(key, redirectState);
      } else {
        sessionStorage.removeItem(key);
      }
    } catch {
      // Storage disabled: sign-in cannot survive the redirect, but nothing leaks either.
    }
  }

  remove(key: string): void {
    this.memory.delete(key);
    try {
      sessionStorage.removeItem(key);
    } catch {
      // Nothing to remove if storage is unavailable.
    }
  }

  clear(): void {
    for (const key of this.memory.keys()) {
      this.remove(key);
    }
  }

  private persisted(key: string): string | null {
    try {
      const stored = sessionStorage.getItem(key);
      return stored === null ? null : MemorySecurityStorage.redirectStateOf(stored);
    } catch {
      return null;
    }
  }

  /** The redirect keys of a serialised entry, or null when it holds none. */
  private static redirectStateOf(value: string): string | null {
    let parsed: unknown;
    try {
      parsed = JSON.parse(value);
    } catch {
      return null;
    }
    if (!parsed || typeof parsed !== 'object') {
      return null;
    }
    const kept: Record<string, unknown> = {};
    for (const key of MemorySecurityStorage.REDIRECT_KEYS) {
      const entry = (parsed as Record<string, unknown>)[key];
      if (entry !== undefined && entry !== null && entry !== '' && entry !== false) {
        kept[key] = entry;
      }
    }
    return Object.keys(kept).length > 0 ? JSON.stringify(kept) : null;
  }
}
