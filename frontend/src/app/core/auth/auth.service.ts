import { Injectable, computed, inject, signal } from '@angular/core';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { firstValueFrom } from 'rxjs';

export type Role = 'SUPER_ADMIN' | 'ADMIN' | 'OPERATOR' | 'READ_ONLY';

interface AccessTokenClaims {
  preferred_username?: string;
  name?: string;
  email?: string;
  realm_access?: { roles?: string[] };
  groups?: string[];
}

/**
 * Who is signed in, and what the console should offer them.
 *
 * Everything here is advisory. The server re-evaluates every request against the caller's grants,
 * so these signals decide what to *show*, never what is allowed. Hiding an action the server
 * would refuse is a courtesy; relying on that hiding for safety would not be.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly oidc = inject(OidcSecurityService);

  readonly authenticated = signal(false);
  readonly username = signal<string | null>(null);
  readonly roles = signal<ReadonlySet<Role>>(new Set());
  readonly groups = signal<readonly string[]>([]);

  readonly initials = computed(() => {
    const name = this.username();
    return name ? name.slice(0, 2).toUpperCase() : '??';
  });

  /**
   * Realm roles set a floor and a ceiling; the per-domain detail comes from the server. These
   * only gate the parts of the console that are globally scoped, such as registering an NGINX
   * instance.
   */
  readonly isSuperAdmin = computed(() => this.roles().has('SUPER_ADMIN'));
  readonly isReadOnly = computed(
    () => this.roles().has('READ_ONLY') && !this.roles().has('ADMIN') && !this.roles().has('SUPER_ADMIN')
      && !this.roles().has('OPERATOR'),
  );

  /**
   * Completes once the session has been established or ruled out.
   *
   * Returned as a promise so it can gate application start-up: the router must not decide whether
   * a route is reachable before this has answered.
   */
  initialise(): Promise<void> {
    const settled = firstValueFrom(this.oidc.checkAuth()).then(({ isAuthenticated, accessToken }) => {
      this.authenticated.set(isAuthenticated);
      if (isAuthenticated) {
        this.readClaims(accessToken);
      }
    });

    // Keep the projection in step with silent renewals, which issue a new token with possibly
    // different group membership.
    this.oidc.getAccessToken().subscribe((token) => {
      if (token) {
        this.readClaims(token);
      }
    });

    return settled;
  }

  login(): void {
    this.oidc.authorize();
  }

  logout(): void {
    this.oidc.logoff().subscribe();
  }

  /**
   * Reads roles and groups from the access token rather than from the id token, because the API
   * authorises on exactly these claims. Decoding is display-only: nothing here is trusted, and a
   * forged token would fail signature validation at the server.
   */
  private readClaims(accessToken: string): void {
    const claims = decodeJwtPayload(accessToken);
    if (!claims) {
      return;
    }
    this.username.set(claims.preferred_username ?? claims.name ?? null);
    this.groups.set(claims.groups ?? []);

    const known: Role[] = ['SUPER_ADMIN', 'ADMIN', 'OPERATOR', 'READ_ONLY'];
    const roles = new Set<Role>();
    for (const role of claims.realm_access?.roles ?? []) {
      if ((known as string[]).includes(role)) {
        roles.add(role as Role);
      }
    }
    this.roles.set(roles);
  }
}

function decodeJwtPayload(token: string): AccessTokenClaims | null {
  try {
    const payload = token.split('.')[1];
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(decodeURIComponent(escape(json)));
  } catch {
    return null;
  }
}
